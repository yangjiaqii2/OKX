"""Verify historical migrations with synthetic rows in a disposable local MySQL 8.4."""
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile
import time
import zipfile


PROJECT = Path(__file__).resolve().parents[1]
DOCKER = ["docker", "--host", "unix:///var/run/docker.sock"]
JAVA_PROBE = """
import org.flywaydb.core.Flyway;

class Probe {
    public static void main(String[] args) {
        var flyway = Flyway.configure()
            .dataSource(System.getenv("FIXTURE_URL"), "fixture", System.getenv("FIXTURE_PASSWORD"))
            .locations("filesystem:" + args[0])
            .target(args[1])
            .baselineOnMigrate(false)
            .createSchemas(false)
            .validateOnMigrate(true)
            .cleanDisabled(true)
            .load();
        int count = flyway.migrate().migrationsExecuted;
        if (count != Integer.parseInt(args[2])) {
            throw new IllegalStateException("Unexpected migration count " + count);
        }
        System.out.printf("V%s: %d migration(s)%n", args[1], count);
    }
}
"""
HISTORICAL_FIELDS = """
SELECT id,cl_ord_id,status,user_name FROM trade_order ORDER BY id;
SELECT id,client_order_id,status,user_name FROM pending_order_state ORDER BY id;
SELECT user_name,inst_id,pos_side,size_value FROM exchange_position_state;
SELECT user_name,bill_id,balance_change FROM account_bill;
SELECT username,password_hash,role FROM quant_auth_user;
SELECT user_name,api_key_encoded,secret_encoded,passphrase_encoded FROM quant_okx_credential;
"""


def run(args, *, env, stdin=None):
    result = subprocess.run(args, input=stdin, env=env, capture_output=True, text=True)
    if result.returncode:
        # Child commands receive synthetic credentials via their environment.
        # Only display diagnostic text after redacting these credentials.
        error = result.stderr + result.stdout
        for key in ("MYSQL_ROOT_PASSWORD", "MYSQL_PASSWORD", "FIXTURE_PASSWORD"):
            if env.get(key):
                error = error.replace(env[key], "[redacted]")
        raise RuntimeError(error[-2400:])
    return result.stdout.strip()


def check_migrations(temp_dir, container, password, env):
    def query(statement):
        return run(DOCKER + [
            "exec", "-i", container, "sh", "-c",
            'export MYSQL_PWD="$MYSQL_PASSWORD"; exec mysql --protocol=TCP -u fixture -BN miren_fixture',
        ], env=env, stdin=statement)

    for _ in range(120):
        try:
            query("SELECT 1")
            break
        except RuntimeError:
            time.sleep(0.5)
    else:
        raise RuntimeError("Local MySQL fixture did not become ready within 60 seconds")

    port = run(DOCKER + ["port", container, "3306/tcp"], env=env)
    host, port = port.rsplit(":", 1)
    if host != "127.0.0.1" or not port.isdigit():
        raise RuntimeError("Expected loopback-only port mapping")
    jdbc = f"jdbc:mysql://{host}:{port}/miren_fixture?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
    java = temp_dir / "Probe.java"
    java.write_text(JAVA_PROBE)

    def migrate(version, expected_count):
        output = run([
            "java", "-cp", str(temp_dir / "lib" / "*"), str(java),
            str(PROJECT / "src/main/resources/db/migration"), str(version), str(expected_count),
        ], env={**env, "FIXTURE_PASSWORD": password, "FIXTURE_URL": jdbc})
        print(output.splitlines()[-1], flush=True)

    migrate(14, 14)
    query("""
        INSERT INTO trade_order (order_role,inst_id,side,ord_type,td_mode,status,cl_ord_id)
          VALUES ('ENTRY','MOCK','buy','limit','cross','FILLED','test-history-order');
        INSERT INTO pending_order_state (id,market_type,inst_id,action,client_order_id,status,created_at,expire_at)
          VALUES ('test-history-pending','CONTRACT','MOCK','BUY','test-cid','FILLED',NOW(),NOW());
    """)
    migrate(21, 7)
    owners = query("SELECT user_name FROM trade_order UNION ALL SELECT user_name FROM pending_order_state")
    if owners.splitlines() != ["local-admin", "local-admin"]:
        raise AssertionError("V15/V16 legacy order owner mapping changed")
    query("""
        INSERT INTO exchange_position_state (user_name,inst_id,pos_side,size_value,ownership,reason,updated_at)
          VALUES ('fixture','MOCK','long',2.5,'OWNED','fixture',NOW());
        INSERT INTO account_bill (user_name,bill_id,bill_type,bill_sub_type,category,balance_change,bill_time)
          VALUES ('fixture','test-bill','77','4','fixture',1.75,NOW());
        INSERT INTO quant_auth_user (username,password_hash,role) VALUES ('fixture','fixture_hash','USER');
        INSERT INTO quant_okx_credential
          (user_name,api_key_encoded,secret_encoded,passphrase_encoded,masked_api_key,active)
          VALUES ('fixture','placeholder1','placeholder2','placeholder3','mock',false);
    """)
    before = query(HISTORICAL_FIELDS)
    migrate(51, 29)
    query("""
        INSERT INTO waiting_entry_intent
          (intent_id,decision_client_order_id,active_key,user_name,strategy_id,inst_id,direction,wait_type,
           reference_price,invalidation_price,decision_candle_ts,expire_at,status,created_at,updated_at,pending_order_id)
          VALUES ('a','a','a','fixture','fixture','MOCK','LONG','WAIT',1,1,NOW(),NOW(),'WAITING',NOW(),NOW(),NULL),
                 ('b','b','b','fixture','fixture','MOCK','LONG','WAIT',1,1,NOW(),NOW(),'WAITING',NOW(),NOW(),'linked');
    """)
    migrate(53, 2)
    after = query("SELECT intent_id,status FROM waiting_entry_intent ORDER BY intent_id")
    if after.splitlines() != ["a\tCANCELED", "b\tWAITING"]:
        raise AssertionError("V52 changed linked intent or retained unsubmitted intent")
    migrate(53, 0)
    if before != query(HISTORICAL_FIELDS):
        raise AssertionError("Historical fixture fields changed during migration or repeat migration")
    print("Historical fixtures verified on MySQL", query("SELECT VERSION()"))


def main():
    archive = PROJECT / "target/quant-assistant-0.0.1-SNAPSHOT.jar"
    if not archive.is_file():
        raise SystemExit("Build the application first with mvn -DskipTests package")
    with tempfile.TemporaryDirectory(prefix="okx-miren-migrations-") as tmp:
        temp_dir = Path(tmp)
        lib = temp_dir / "lib"
        lib.mkdir()
        with zipfile.ZipFile(archive) as jar:
            for item in jar.namelist():
                if item.startswith("BOOT-INF/lib/") and item.endswith(".jar"):
                    with jar.open(item) as source, (lib / Path(item).name).open("wb") as dest:
                        shutil.copyfileobj(source, dest)
        env = {"PATH": "/usr/local/bin:/usr/bin:/bin", "HOME": tmp, "LANG": "C.UTF-8"}
        password = secrets.token_urlsafe(32)
        container = run(DOCKER + [
            "run", "--pull=never", "--rm", "-d", "--tmpfs", "/var/lib/mysql",
            "-e", "MYSQL_DATABASE=miren_fixture", "-e", "MYSQL_USER=fixture",
            "-e", "MYSQL_ROOT_PASSWORD", "-e", "MYSQL_PASSWORD", "-p", "127.0.0.1::3306",
            "mysql:8.4", "--innodb-buffer-pool-size=64M",
        ], env={**env, "MYSQL_ROOT_PASSWORD": secrets.token_urlsafe(32), "MYSQL_PASSWORD": password})
        try:
            check_migrations(temp_dir, container, password, env)
        finally:
            run(DOCKER + ["stop", "-t", "10", container], env=env)


if __name__ == "__main__":
    main()
