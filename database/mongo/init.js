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

// Banking demo collection in a dedicated `banking` database — matches
// MONGO_LINK's service_name. We avoid Mongo's `admin` database because
// the DataDirect MongoDB ODBC driver (used by ADB's heterogeneous
// gateway) won't surface collections there for DB_LINK catalog lookups.

const bank = db.getSiblingDB('banking');
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

print('Mongo init.js complete. Collection: banking.support_tickets');

// Agent-demo seed: rich narratives + routine tickets keyed by customer_id
// (matches Oracle Free customers.id). Idempotent.
if (bank.support_tickets.countDocuments({ ticket_id: { $gte: 871 } }) === 0) {
  bank.support_tickets.insertMany([
    // --- narrative tickets ---
    { ticket_id: 871,  customer_id: 2, customer: 'Bob Chen',     subject: 'Duplicate charge dispute (resolved)',           body: 'Refund issued for duplicate $129.00 post at Best Buy. Provisional credit applied within Reg E window.', channel: 'EMAIL', status: 'RESOLVED',    priority: 'MED',  created_at: ISODate('2025-08-19T14:22:00Z'), updated_at: ISODate('2025-08-22T09:00:00Z') },
    { ticket_id: 1042, customer_id: 3, customer: 'Carol Diaz',   subject: 'What is the daily wire limit?',                  body: 'Customer asking what the daily outbound wire limit is on her checking account. Asked specifically about international wires.', channel: 'CHAT', status: 'RESOLVED', priority: 'LOW', created_at: ISODate('2026-03-12T15:48:00Z'), updated_at: ISODate('2026-03-12T16:05:00Z') },
    { ticket_id: 1051, customer_id: 2, customer: 'Bob Chen',     subject: 'Duplicate $230 charge from Acme Hardware',       body: 'I see two identical $230 charges from Acme Hardware four minutes apart on April 13. I only made one purchase. Please investigate and refund the duplicate.', channel: 'EMAIL', status: 'OPEN', priority: 'HIGH', created_at: ISODate('2026-04-15T10:14:00Z'), updated_at: ISODate('2026-04-15T10:14:00Z') },
    { ticket_id: 1056, customer_id: 1, customer: 'Alice Morgan', subject: 'Address change request',                         body: 'I moved last week. Please update my address on file. New: 412 Elm St, Brooklyn, NY 11215. Note: my driver\'s license shows the old address until I renew next month.', channel: 'CHAT', status: 'OPEN', priority: 'MED', created_at: ISODate('2026-04-11T11:30:00Z'), updated_at: ISODate('2026-04-11T11:30:00Z') },
    { ticket_id: 1063, customer_id: 4, customer: 'Jamal Reed',   subject: 'Lost card — please reissue',                     body: 'I cannot find my debit card and I think it was stolen at the gas station yesterday. Please cancel it and send a replacement to my address on file.', channel: 'PHONE', status: 'IN_PROGRESS', priority: 'HIGH', created_at: ISODate('2026-04-15T09:02:00Z'), updated_at: ISODate('2026-04-15T09:35:00Z') },
    // --- routine tickets, one per customer 6-20 ---
    { ticket_id: 1101, customer_id: 6,  customer: 'Marco Russo',   subject: 'Statement download not working',         body: 'PDF download fails on March statement.',         channel: 'CHAT',  status: 'RESOLVED',    priority: 'LOW',  created_at: ISODate('2026-03-28T10:00:00Z'), updated_at: ISODate('2026-03-28T15:00:00Z') },
    { ticket_id: 1102, customer_id: 7,  customer: 'Yuki Tanaka',   subject: 'Add payee to bill pay',                  body: 'Please add Tokyo Gas to bill pay.',              channel: 'EMAIL', status: 'RESOLVED',    priority: 'LOW',  created_at: ISODate('2026-03-30T08:00:00Z'), updated_at: ISODate('2026-03-30T11:00:00Z') },
    { ticket_id: 1103, customer_id: 8,  customer: 'Sara Cohen',    subject: 'Travel notice — Israel May 5-19',         body: 'Going to Israel May 5-19, please flag.',         channel: 'CHAT',  status: 'CLOSED',      priority: 'LOW',  created_at: ISODate('2026-04-02T14:00:00Z'), updated_at: ISODate('2026-04-02T14:30:00Z') },
    { ticket_id: 1104, customer_id: 9,  customer: 'Liam Walsh',    subject: 'ATM withdrawal limit increase',          body: 'Requesting daily ATM limit raise to £800.',      channel: 'PHONE', status: 'IN_PROGRESS', priority: 'MED',  created_at: ISODate('2026-04-04T09:00:00Z'), updated_at: ISODate('2026-04-04T16:00:00Z') },
    { ticket_id: 1105, customer_id: 10, customer: 'Aisha Khan',    subject: 'Wire transfer fee inquiry',              body: 'What is the fee for international wires?',       channel: 'EMAIL', status: 'RESOLVED',    priority: 'LOW',  created_at: ISODate('2026-04-05T11:00:00Z'), updated_at: ISODate('2026-04-05T13:00:00Z') },
    { ticket_id: 1106, customer_id: 11, customer: 'Diego Vargas',  subject: 'Direct deposit setup',                   body: 'New employer; please send direct-deposit form.', channel: 'CHAT',  status: 'RESOLVED',    priority: 'LOW',  created_at: ISODate('2026-04-06T10:00:00Z'), updated_at: ISODate('2026-04-06T11:00:00Z') },
    { ticket_id: 1107, customer_id: 12, customer: 'Mei Lin',       subject: 'Mobile deposit not posting',             body: 'Check from 04/02 still not in account.',         channel: 'CHAT',  status: 'OPEN',        priority: 'MED',  created_at: ISODate('2026-04-09T09:00:00Z'), updated_at: ISODate('2026-04-09T09:00:00Z') },
    { ticket_id: 1108, customer_id: 13, customer: 'Olu Adebayo',   subject: 'KYC document submission',                body: 'Submitting passport scan to complete KYC.',      channel: 'EMAIL', status: 'IN_PROGRESS', priority: 'HIGH', created_at: ISODate('2026-04-09T16:00:00Z'), updated_at: ISODate('2026-04-10T08:00:00Z') },
    { ticket_id: 1109, customer_id: 14, customer: 'Tomas Herrera', subject: 'Joint account holder addition',          body: 'Add my partner Lucia to checking account.',      channel: 'PHONE', status: 'IN_PROGRESS', priority: 'LOW',  created_at: ISODate('2026-04-10T13:00:00Z'), updated_at: ISODate('2026-04-11T10:00:00Z') },
    { ticket_id: 1110, customer_id: 15, customer: 'Hannah Berg',   subject: 'Overdraft protection enrollment',         body: 'Please enroll savings as overdraft buffer.',     channel: 'CHAT',  status: 'RESOLVED',    priority: 'LOW',  created_at: ISODate('2026-04-11T11:00:00Z'), updated_at: ISODate('2026-04-11T13:00:00Z') },
    { ticket_id: 1111, customer_id: 16, customer: 'Ravi Menon',    subject: 'International transfer rate question',    body: 'INR transfer to Mumbai — current FX rate?',      channel: 'EMAIL', status: 'RESOLVED',    priority: 'LOW',  created_at: ISODate('2026-04-12T09:00:00Z'), updated_at: ISODate('2026-04-12T10:30:00Z') },
    { ticket_id: 1112, customer_id: 17, customer: 'Elena Petrova', subject: 'Statement archive request',              body: 'Need 2024 statements for tax filing.',           channel: 'CHAT',  status: 'CLOSED',      priority: 'LOW',  created_at: ISODate('2026-04-12T14:00:00Z'), updated_at: ISODate('2026-04-12T14:20:00Z') },
    { ticket_id: 1113, customer_id: 18, customer: 'Jonas Lind',    subject: 'New checking account inquiry',           body: 'Want to open second checking for business.',     channel: 'EMAIL', status: 'OPEN',        priority: 'LOW',  created_at: ISODate('2026-04-13T10:00:00Z'), updated_at: ISODate('2026-04-13T10:00:00Z') },
    { ticket_id: 1114, customer_id: 19, customer: 'Fatima Hassan', subject: 'Travel notice — Egypt April 20-30',      body: 'Travelling to Egypt next week.',                 channel: 'CHAT',  status: 'CLOSED',      priority: 'LOW',  created_at: ISODate('2026-04-14T08:00:00Z'), updated_at: ISODate('2026-04-14T08:15:00Z') },
    { ticket_id: 1115, customer_id: 20, customer: 'Ben Wright',    subject: 'Credit card credit-limit increase',      body: 'Requesting CL increase from $5K to $10K.',       channel: 'PHONE', status: 'IN_PROGRESS', priority: 'LOW',  created_at: ISODate('2026-04-14T15:00:00Z'), updated_at: ISODate('2026-04-15T09:00:00Z') },
  ]);
}

print('Mongo init.js extended seed complete: 5 narrative + 15 routine tickets inserted (if absent).');
