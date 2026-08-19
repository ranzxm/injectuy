#!/bin/sh

# Find base dir
DIR="$(cd "$(dirname "$0")" && pwd)"

# Check if Gradle Wrapper jar exists, if not download standard gradle runner
if [ ! -f "$DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
    mkdir -p "$DIR/gradle/wrapper"
    curl -sLo "$DIR/gradle/wrapper/gradle-wrapper.jar" "https://raw.githubusercontent.com/gradle/gradle/v8.2.0/gradle/wrapper/gradle-wrapper.jar"
fi

exec java -jar "$DIR/gradle/wrapper/gradle-wrapper.jar" "$@"
