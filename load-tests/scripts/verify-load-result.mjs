#!/usr/bin/env node
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const metadataPath = resolve(repoRoot, process.env.METADATA_FILE || 'load-tests/out/metadata.json');
const metadata = existsSync(metadataPath) ? JSON.parse(readFileSync(metadataPath, 'utf8')) : {};

const cfg = {
  infra: process.env.LOAD_TEST_INFRA || 'auto',
  offerId: intEnv('OFFER_ID', metadata.offerId || 900001),
  users: intEnv('USERS', metadata.users || 5000),
  stock: intEnv('STOCK', metadata.stock || 100),
  expectedAccepted: intEnv('EXPECTED_ACCEPTED', metadata.expectedAccepted || 100),
  userIdStart: intEnv('USER_ID_START', metadata.userIdStart || 1000000),
  mysqlContainer: process.env.MYSQL_CONTAINER || 'flash-sale-platform-mysql',
  mysqlDatabase: process.env.MYSQL_DATABASE || 'flash_sale_platform',
  mysqlHost: process.env.MYSQL_HOST || '127.0.0.1',
  mysqlPort: process.env.MYSQL_PORT || '3306',
  mysqlUser: process.env.MYSQL_USERNAME || 'root',
  mysqlPassword: process.env.MYSQL_PASSWORD,
  mysqlRootPassword: process.env.MYSQL_ROOT_PASSWORD,
  redisContainer: process.env.REDIS_CONTAINER || 'flash-sale-platform-redis',
  redisHost: process.env.REDIS_HOST || '127.0.0.1',
  redisPort: process.env.REDIS_PORT || '6379',
  redisPassword: process.env.REDIS_PASSWORD || '',
  rabbitContainer: process.env.RABBITMQ_CONTAINER || 'flash-sale-platform-rabbitmq',
  rabbitManagementUrl: (process.env.RABBITMQ_MANAGEMENT_URL || 'http://localhost:15672').replace(/\/$/, ''),
  rabbitManagementUsername: process.env.RABBITMQ_MANAGEMENT_USERNAME || 'guest',
  rabbitManagementPassword: process.env.RABBITMQ_MANAGEMENT_PASSWORD || 'guest',
  rabbitVhost: process.env.RABBITMQ_VHOST || '/',
  waitSeconds: intEnv('WAIT_SECONDS', 60),
  pollIntervalMs: intEnv('POLL_INTERVAL_MS', 2000),
};

cfg.resolvedInfra = resolveInfra(cfg.infra);
if (cfg.mysqlPassword === undefined) {
  cfg.mysqlPassword = cfg.resolvedInfra === 'docker' ? (cfg.mysqlRootPassword || 'root') : '';
}
cfg.userIdEnd = cfg.userIdStart + cfg.users - 1;

const started = Date.now();
let snapshot;
while (Date.now() - started <= cfg.waitSeconds * 1000) {
  snapshot = await collectSnapshot();
  printSnapshot(snapshot);
  if (isAsyncWorkDrained(snapshot)) {
    break;
  }
  await sleep(cfg.pollIntervalMs);
}

const failures = validate(snapshot);
if (failures.length > 0) {
  console.error('\nLoad-test verification failed:');
  for (const failure of failures) {
    console.error(`- ${failure}`);
  }
  process.exit(1);
}

console.log('\nLoad-test verification passed.');

async function collectSnapshot() {
  const sql = `
SELECT
  (SELECT COUNT(*) FROM orders WHERE offer_id = ${cfg.offerId} AND user_id BETWEEN ${cfg.userIdStart} AND ${cfg.userIdEnd}) AS order_count,
  (SELECT COUNT(*) FROM (
    SELECT user_id FROM orders WHERE offer_id = ${cfg.offerId} GROUP BY user_id HAVING COUNT(*) > 1
  ) duplicate_users) AS duplicate_user_count,
  (SELECT COALESCE(stock, -1) FROM flash_sale_offers WHERE offer_id = ${cfg.offerId}) AS db_stock,
  (SELECT COUNT(*) FROM payment_order WHERE order_id IN (SELECT id FROM orders WHERE offer_id = ${cfg.offerId})) AS payment_order_count;
`;
  const row = mysqlScalarRow(sql);
  return {
    orderCount: Number(row[0]),
    duplicateUserCount: Number(row[1]),
    dbStock: Number(row[2]),
    paymentOrderCount: Number(row[3]),
    redisStock: Number(redis(['GET', `flashsale:stock:${cfg.offerId}`]) || 0),
    redisReservations: Number(redis(['SCARD', `flashsale:order:${cfg.offerId}`]) || 0),
    queues: await rabbitQueues(),
  };
}

function validate(s) {
  const failures = [];
  const expectedRemainingStock = cfg.stock - cfg.expectedAccepted;
  const dlqDepth = queueDepth(s, 'flashsale.order.create.dlq');

  if (s.orderCount !== cfg.expectedAccepted) {
    failures.push(`orders expected ${cfg.expectedAccepted}, got ${s.orderCount}`);
  }
  if (s.duplicateUserCount !== 0) {
    failures.push(`duplicate user orders expected 0, got ${s.duplicateUserCount}`);
  }
  if (s.dbStock !== expectedRemainingStock) {
    failures.push(`database stock expected ${expectedRemainingStock}, got ${s.dbStock}`);
  }
  if (s.redisStock !== expectedRemainingStock) {
    failures.push(`Redis stock expected ${expectedRemainingStock}, got ${s.redisStock}`);
  }
  if (s.redisReservations !== cfg.expectedAccepted) {
    failures.push(`Redis reservation count expected ${cfg.expectedAccepted}, got ${s.redisReservations}`);
  }
  if (!isAsyncWorkDrained(s)) {
    failures.push('RabbitMQ create/retry queues are not drained');
  }
  if (dlqDepth !== 0) {
    failures.push(`RabbitMQ dead-letter queue expected 0, got ${dlqDepth}`);
  }
  return failures;
}

function isAsyncWorkDrained(s) {
  return queueDepth(s, 'flashsale.order.create.queue') === 0 &&
    queueDepth(s, 'flashsale.order.create.retry.queue') === 0;
}

function queueDepth(s, queueName) {
  const queue = s.queues[queueName];
  if (!queue) {
    return 0;
  }
  return queue.ready + queue.unacknowledged;
}

function printSnapshot(s) {
  console.log(
    [
      '',
      `orders=${s.orderCount}`,
      `duplicateUsers=${s.duplicateUserCount}`,
      `dbStock=${s.dbStock}`,
      `redisStock=${s.redisStock}`,
      `redisReservations=${s.redisReservations}`,
      `createQueue=${queueDepth(s, 'flashsale.order.create.queue')}`,
      `retryQueue=${queueDepth(s, 'flashsale.order.create.retry.queue')}`,
      `dlq=${queueDepth(s, 'flashsale.order.create.dlq')}`,
    ].join(' ')
  );
}

function mysqlScalarRow(sql) {
  const output = cfg.resolvedInfra === 'docker'
    ? run(
      'docker',
      [
        'exec',
        '-i',
        cfg.mysqlContainer,
        'mysql',
        '-N',
        '-B',
        '-uroot',
        `-p${cfg.mysqlPassword}`,
        cfg.mysqlDatabase,
      ],
      sql
    )
    : run('mysql', mysqlArgs('-N', '-B'), sql);
  const line = output.trim().split('\n').filter(Boolean).pop();
  if (!line) {
    throw new Error('MySQL query returned no rows.');
  }
  return line.split('\t');
}

function redis(args) {
  if (cfg.resolvedInfra === 'docker') {
    return run('docker', ['exec', cfg.redisContainer, 'redis-cli', ...args]).trim();
  }
  return run('redis-cli', redisArgs(...args)).trim();
}

async function rabbitQueues() {
  if (cfg.resolvedInfra === 'local') {
    return rabbitQueuesViaHttp();
  }

  const output = run(
    'docker',
    ['exec', cfg.rabbitContainer, 'rabbitmqctl', '-q', 'list_queues', 'name', 'messages_ready', 'messages_unacknowledged']
  );
  const queues = {};
  for (const line of output.trim().split('\n')) {
    const parts = line.trim().split(/\s+/);
    if (parts.length !== 3 || parts[0] === 'name') {
      continue;
    }
    queues[parts[0]] = {
      ready: Number(parts[1]),
      unacknowledged: Number(parts[2]),
    };
  }
  return queues;
}

async function rabbitQueuesViaHttp() {
  const encodedVhost = encodeURIComponent(cfg.rabbitVhost);
  const response = await fetch(`${cfg.rabbitManagementUrl}/api/queues/${encodedVhost}`, {
    headers: {
      Authorization: `Basic ${Buffer.from(`${cfg.rabbitManagementUsername}:${cfg.rabbitManagementPassword}`).toString('base64')}`,
    },
  });
  if (!response.ok) {
    throw new Error(`RabbitMQ Management API failed with HTTP ${response.status}`);
  }
  const body = await response.json();
  const queues = {};
  for (const queue of body) {
    queues[queue.name] = {
      ready: Number(queue.messages_ready || 0),
      unacknowledged: Number(queue.messages_unacknowledged || 0),
    };
  }
  return queues;
}

function run(command, args, input) {
  const result = spawnSync(command, args, {
    input,
    encoding: 'utf8',
    maxBuffer: 1024 * 1024 * 20,
  });
  if (result.status !== 0) {
    throw new Error(
      [
        `Command failed: ${command} ${args.join(' ')}`,
        result.stdout && `stdout:\n${result.stdout}`,
        result.stderr && `stderr:\n${result.stderr}`,
      ]
        .filter(Boolean)
        .join('\n')
    );
  }
  return result.stdout;
}

function intEnv(name, fallback) {
  const value = process.env[name];
  if (value === undefined || value === '') {
    return fallback;
  }
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < 0) {
    throw new Error(`${name} must be a non-negative integer.`);
  }
  return parsed;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function resolveInfra(value) {
  if (value === 'docker' || value === 'local') {
    return value;
  }
  if (value !== 'auto') {
    throw new Error('LOAD_TEST_INFRA must be one of: auto, docker, local.');
  }
  return commandExists('docker') ? 'docker' : 'local';
}

function commandExists(command) {
  const result = spawnSync('sh', ['-c', `command -v ${command}`], {
    encoding: 'utf8',
  });
  return result.status === 0;
}

function mysqlArgs(...extraArgs) {
  const args = [...extraArgs, '-h', cfg.mysqlHost, '-P', cfg.mysqlPort, '-u', cfg.mysqlUser];
  if (cfg.mysqlPassword) {
    args.push(`-p${cfg.mysqlPassword}`);
  }
  args.push(cfg.mysqlDatabase);
  return args;
}

function redisArgs(...extraArgs) {
  const args = ['-h', cfg.redisHost, '-p', cfg.redisPort];
  if (cfg.redisPassword) {
    args.push('-a', cfg.redisPassword);
  }
  return [...args, ...extraArgs];
}
