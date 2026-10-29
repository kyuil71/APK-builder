#!/usr/bin/env bash
# 판단 엔진(순수 Kotlin) 테스트. 필요: kotlinc + java
set -euo pipefail
cd "$(dirname "$0")/.."
kotlinc app/src/main/java/com/kyuil71/roadguardian/core/*.kt tests/CoreTest.kt -include-runtime -d /tmp/rg-core-tests.jar
java -Dfile.encoding=UTF-8 -jar /tmp/rg-core-tests.jar
