#!/bin/bash
set -e

echo "Waiting for MinIO to start..."
sleep 5

mc alias set myminio http://localhost:9000 minioadmin minioadmin || true
mc mb myminio/uploads --ignore-existing || true

echo "MinIO initialized successfully"