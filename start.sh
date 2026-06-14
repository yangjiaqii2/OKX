#!/bin/bash
mvn -DskipTests package && java -jar target/quant-assistant-0.0.1-SNAPSHOT.jar