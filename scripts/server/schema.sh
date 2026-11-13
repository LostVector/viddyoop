#!/bin/sh
set -eu
export SQL_PASSWORD="$(cat /run/secrets/postgres-password)"
for pair in temporal:temporal temporal_visibility:visibility; do
    database=${pair%:*}
    directory=${pair#*:}
    temporal-sql-tool --plugin postgres12 --ep postgres -p 5432 -u temporal --db "$database" setup-schema -v 0.0
    temporal-sql-tool --plugin postgres12 --ep postgres -p 5432 -u temporal --db "$database" update-schema \
        -d "/etc/temporal/schema/postgresql/v12/$directory/versioned"
done
