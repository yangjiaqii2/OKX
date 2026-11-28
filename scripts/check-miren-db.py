#!/usr/bin/env python3
"""Inspect only the okx-quant Addon session. Exit 0: literal scope, 2: wildcard scope, 1: failure."""
import argparse
import base64
import os
from pathlib import Path
import subprocess
import tempfile


JAVA_PROBE = r"""
import java.sql.*;
import java.util.*;

public class OkxDbCheck {
    static final String DB = "okx_quant";
    static final String ACCOUNT = "`okx_quant`@`%`";

    static void require(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }

    static int check(String migrationList) throws SQLException {
        Map<String,String> env = System.getenv();
        require(DB.equals(env.get("MYSQL_DATABASE")) && DB.equals(env.get("MYSQL_USER")),
                "Addon database or username differs from expected okx_quant");
        String url = "jdbc:mysql://" + env.get("MYSQL_HOST") + ":" + env.get("MYSQL_PORT") + "/" + DB
                + "?connectionTimeZone=UTC&connectTimeout=5000&socketTimeout=10000";
        try (Connection conn = DriverManager.getConnection(url, DB, env.get("MYSQL_PASSWORD"))) {
            conn.setReadOnly(true);
            try (Statement sql = conn.createStatement()) {
                boolean partial;
                try (ResultSet r = sql.executeQuery("SELECT DATABASE(),CURRENT_USER(),CURRENT_ROLE(),@@GLOBAL.partial_revokes")) {
                    r.next();
                    require(DB.equals(r.getString(1)) && "okx_quant@%".equals(r.getString(2)),
                            "Unexpected database or authenticated principal");
                    require("NONE".equals(r.getString(3)), "Account has an active role");
                    partial = r.getBoolean(4);
                    System.out.printf("database=%s current_user=%s active_roles=NONE partial_revokes=%s%n",
                            DB, r.getString(2), partial ? "ON" : "OFF");
                }
                boolean dbGrant = false;
                boolean wildcard = false;
                try (ResultSet r = sql.executeQuery("SHOW GRANTS FOR CURRENT_USER()")) {
                    while (r.next()) {
                        String grant = r.getString(1);
                        if (grant.equals("GRANT USAGE ON *.* TO " + ACCOUNT)) continue;
                        boolean plain = grant.equals("GRANT ALL PRIVILEGES ON `okx_quant`.* TO " + ACCOUNT);
                        boolean escaped = grant.equals("GRANT ALL PRIVILEGES ON `okx\\_quant`.* TO " + ACCOUNT);
                        // No grant option, global privileges, roles, table grants or foreign schema grants.
                        require(plain || (escaped && !partial), "Unexpected privilege scope or grant syntax");
                        dbGrant = true;
                        wildcard |= plain && !partial;
                    }
                }
                require(dbGrant, "Expected Addon database grant missing");
                try (ResultSet r = sql.executeQuery("SELECT COUNT(*) FROM information_schema.schemata "
                        + "WHERE schema_name NOT IN (DATABASE(),'information_schema','performance_schema','mysql','sys')")) {
                    r.next();
                    System.out.println("other_visible_business_schemas=" + r.getInt(1));
                    require(r.getInt(1) == 0, "Other business schemas visible");
                }

                Set<String> expected = new HashSet<>(List.of(migrationList.split(",")));
                Set<String> applied = new HashSet<>();
                int failed = 0;
                String latest = "none";
                try (ResultSet r = sql.executeQuery("SELECT version,success FROM flyway_schema_history ORDER BY installed_rank")) {
                    while (r.next()) {
                        if (!r.getBoolean(2)) { failed++; continue; }
                        String version = r.getString(1);
                        if (version != null) {
                            require(applied.add(version), "Duplicate successful version in Flyway history");
                            latest = version;
                        }
                    }
                }
                System.out.printf("flyway_success=%d flyway_failed=%d last_version=%s image_versions=%d%n",
                        applied.size(), failed, latest, expected.size());
                require(failed == 0 && applied.equals(expected), "Flyway history differs from the deployed JAR");
                System.out.println(wildcard
                        ? "CONDITIONAL: database-level grant contains wildcard underscore with partial_revokes OFF."
                        : "PASS: database-level grant matches only the literal application database.");
                return wildcard ? 2 : 0;
            }
        }
    }

    public static void main(String[] args) {
        try {
            System.exit(check(args[0]));
        } catch (SQLException e) {
            System.err.printf("SQL check failed (state=%s code=%d)%n", e.getSQLState(), e.getErrorCode());
        } catch (IllegalStateException e) {
            System.err.println("Check failed: " + e.getMessage());
        }
        System.exit(1);
    }
}
"""


SANDBOX_PROBE = """
import base64, os, pathlib, re, subprocess, sys, tempfile, zipfile

if os.getenv('MIREN_RUNTIME_APP') != 'okx-quant':
    sys.exit('Unexpected sandbox application')
keys = ('PATH', 'MYSQL_HOST', 'MYSQL_PORT', 'MYSQL_USER', 'MYSQL_DATABASE', 'MYSQL_PASSWORD')
if not all(os.getenv(key) for key in keys):
    sys.exit('Missing MySQL Addon environment')
with tempfile.TemporaryDirectory(prefix='okx-db-check-') as tmp:
    root = pathlib.Path(tmp)
    with zipfile.ZipFile('/app/app.jar') as jar:
        names = jar.namelist()
        drivers = [name for name in names if re.fullmatch(r'BOOT-INF/lib/mysql-connector-j-[^/]+[.]jar', name)]
        versions = [m[1] for name in names if (m := re.fullmatch(
            r'BOOT-INF/classes/db/migration/V([0-9]+)__[^/]+[.]sql', name))]
        if len(drivers) != 1 or not versions or len(versions) != len(set(versions)):
            sys.exit('Cannot identify unique driver and Flyway versions from the application JAR')
        (root / 'driver.jar').write_bytes(jar.read(drivers[0]))
    (root / 'OkxDbCheck.class').write_bytes(base64.b64decode(sys.argv[1], validate=True))
    status = subprocess.run(['java', '-Xms8m', '-Xmx48m', '-XX:ActiveProcessorCount=1',
                             '-cp', tmp + ':' + tmp + '/driver.jar', 'OkxDbCheck', ','.join(versions)],
                            env={key: os.environ[key] for key in keys})
sys.exit(status.returncode)
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("cluster", choices=("local", "tencent-cloud"))
    cluster = parser.parse_args().cluster
    with tempfile.TemporaryDirectory(prefix="okx-db-check-") as tmp:
        source = Path(tmp) / "OkxDbCheck.java"
        source.write_text(JAVA_PROBE)
        subprocess.run(["javac", "--release", "17", str(source)], check=True)
        bytecode = base64.b64encode(source.with_suffix(".class").read_bytes()).decode("ascii")
        cmd = ["miren", "sandbox", "exec", "-C", cluster, "-a", "okx-quant", "--service", "web",
               "--", "/opt/akshare/bin/python", "-Bc", SANDBOX_PROBE, bytecode]
        result = subprocess.run(cmd, env={"HOME": str(Path.home()), "PATH": os.environ["PATH"]},
                                stdin=subprocess.DEVNULL)
        return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
