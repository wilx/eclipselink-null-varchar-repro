#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
maven_command="${MVN:-mvn}"
container_name="eclipselink-null-varchar-repro-$$"
trap 'docker rm --force "$container_name" >/dev/null 2>&1 || true' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

docker run --detach --rm --name "$container_name" \
    --publish 127.0.0.1::5432 \
    --env POSTGRES_DB=eclipselink_repro \
    --env POSTGRES_USER=repro \
    --env POSTGRES_PASSWORD=repro \
    postgres:16

database_ready=false
for attempt in {1..45}; do
    if docker exec "$container_name" pg_isready --host=127.0.0.1 --username=repro --dbname=eclipselink_repro >/dev/null 2>&1; then
        database_ready=true
        break
    fi
    sleep 1
done
if [[ "$database_ready" != true ]]; then
    docker logs "$container_name"
    exit 1
fi

published_address="$(docker port "$container_name" 5432/tcp)"
database_port="${published_address##*:}"
"$maven_command" -B "-Drepro.jdbc.url=jdbc:postgresql://127.0.0.1:${database_port}/eclipselink_repro" \
    test "$@" 2>&1 | tee run.log
