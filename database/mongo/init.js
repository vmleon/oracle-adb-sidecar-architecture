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

// Banking demo collection in the `admin` database — matches MONGO_LINK's
// service_name so the ADB sidecar can reach it via V_SUPPORT_TICKETS.

const bank = db.getSiblingDB('admin');
bank.createCollection('support_tickets');

if (bank.support_tickets.countDocuments({}) === 0) {
  bank.support_tickets.insertMany([
    {
      ticket_id: 1,
      customer: 'Alice Morgan',
      subject: 'Card declined at merchant',
      status: 'open',
      createdAt: new Date(),
    },
    {
      ticket_id: 2,
      customer: 'Bob Chen',
      subject: 'International wire pending > 48h',
      status: 'in_progress',
      createdAt: new Date(),
    },
    {
      ticket_id: 3,
      customer: 'Carol Diaz',
      subject: 'Mobile app login loop',
      status: 'open',
      createdAt: new Date(),
    },
    {
      ticket_id: 4,
      customer: 'Alice Morgan',
      subject: 'Unrecognized transaction $899.99',
      status: 'closed',
      createdAt: new Date(),
    },
  ]);
}

print('Mongo init.js complete. Collection: admin.support_tickets');
