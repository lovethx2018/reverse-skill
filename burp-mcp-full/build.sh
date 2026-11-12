#!/bin/bash
# BurpSuite MCP Full Control - Linux / macOS Build Script
# Requires: JDK 21+
# Produces: build/libs/burp-mcp-full.jar (fat jar, Burp-loadable)

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

bash "$SCRIPT_DIR/gradlew" jar
