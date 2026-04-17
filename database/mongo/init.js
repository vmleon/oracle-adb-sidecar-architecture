// Run with: mongosh "mongodb://admin:<pwd>@<host>:27017/admin" init.js
//
// MongoDB equivalent of the Liquibase deployment_marker — proves end-to-end
// schema/data initialization works on the sidecar Mongo container.

const target = db.getSiblingDB('adbsidecar');

target.createCollection('deployment_marker');

target.deployment_marker.insertOne({
  source: 'mongosh-init',
  createdAt: new Date(),
});

print('Mongo init.js complete. Collection: adbsidecar.deployment_marker');
