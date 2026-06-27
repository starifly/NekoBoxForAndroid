#!/usr/bin/env bash
# Fail if app code introduces real SharedPreferences. SharedPreferences XML files are
# eligible for system backup (allowBackup=true) and could leak settings/credentials. All
# settings must go through the Room-backed RoomPreferenceDataStore (configuration.db, which is
# excluded from backup via backup_rules.xml / backup_descriptor.xml). Shared by the Namespace
# (.github/workflows/ci.yml) and Depot (.depot/workflows/guard.yml) guard jobs.
set -euo pipefail

matches=$(grep -rn \
    -e 'getSharedPreferences' \
    -e 'getDefaultSharedPreferences' \
    -e 'PreferenceManager.getDefaultSharedPreferences' \
    app/src/main/java || true)

if [ -n "$matches" ]; then
    echo "::error::Real SharedPreferences usage found - these are backup-eligible and must not hold settings/credentials. Use DataStore.configurationStore (Room, excluded from backup) instead." >&2
    echo "$matches" >&2
    exit 1
fi
echo "OK: no real SharedPreferences usage."
