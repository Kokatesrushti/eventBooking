#!/usr/bin/env bash
# usage: ./k6/seed.sh https://your-app.onrender.com YOUR_SEED_SECRET [customers] [events] [seats]
BASE=$1; SECRET=$2; CUSTOMERS=${3:-1000}; EVENTS=${4:-1}; SEATS=${5:-100000}
curl -sS -X POST "$BASE/api/admin/seed?customers=$CUSTOMERS&events=$EVENTS&seats=$SEATS" \
  -H "X-Seed-Secret: $SECRET" -o "$(dirname "$0")/seed.json"
echo "Wrote k6/seed.json  ($(wc -c < "$(dirname "$0")/seed.json") bytes)"
