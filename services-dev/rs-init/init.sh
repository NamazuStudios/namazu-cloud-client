# This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
# If a copy of the MPL was not distributed with this file, You can obtain one at
# https://mozilla.org/MPL/2.0/.

#!/bin/sh
set -e

echo "Waiting for mongod to come up..."
until mongosh --quiet --eval "db.adminCommand('ping').ok" >/dev/null 2>&1; do
  sleep 1
done

echo "Initiating replica set..."
if mongosh --quiet --eval "rs.initiate({})" 2>&1; then
  echo "Replica set initiated."
else
  echo "rs.initiate returned a non-zero status (may already be initialised)."
fi

# Exit once the replica set is up so `docker compose up` completes.
sleep 1