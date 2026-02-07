db = db.getSiblingDB('fileUploader');

db.createUser({
    user: 'fileUploader',
    pwd: 'password',
    roles: [
        {
            role: 'readWrite',
            db: 'fileUploader'
        }
    ]
});

db.createCollection('upload_requests');
db.createCollection('file_metadata');

db.upload_requests.createIndex(
    { clientId: 1, uploadId: 1 },
    { unique: true, name: 'client_upload_unique' }
);

db.upload_requests.createIndex(
    { status: 1, updatedAt: 1 },
    { name: 'status_updated_idx' }
);

db.upload_requests.createIndex(
    { createdAt: 1 },
    { expireAfterSeconds: 604800, name: 'ttl_idx' }
);

db.file_metadata.createIndex(
    { uploadRequestId: 1 },
    { unique: true, name: 'upload_request_idx' }
);

db.file_metadata.createIndex(
    { checksum: 1, clientId: 1 },
    { name: 'checksum_client_idx' }
);

print('MongoDB initialized successfully');