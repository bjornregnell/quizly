#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

echo "Running Quizly server tests."
echo "The test suite uses temporary localhost ports, so a local Quizly server may stay running on 8095/8096."

sbt --client server/test
