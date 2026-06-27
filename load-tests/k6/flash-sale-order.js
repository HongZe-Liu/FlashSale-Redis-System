import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';

const baseUrl = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const offerId = __ENV.OFFER_ID || '900001';
const tokenFile = __ENV.TOKEN_FILE || '../out/tokens.json';
const vus = Number(__ENV.VUS || 200);
const iterations = Number(__ENV.ITERATIONS || 5000);
const maxDuration = __ENV.MAX_DURATION || '2m';
const p95ThresholdMs = Number(__ENV.P95_THRESHOLD_MS || 1000);
const p99ThresholdMs = Number(__ENV.P99_THRESHOLD_MS || 3000);

const tokens = new SharedArray('flash-sale-user-tokens', () => JSON.parse(openFirstExistingTokenFile(tokenFile)));

const accepted = new Counter('business_order_accepted');
const rejectedStock = new Counter('business_order_rejected_stock');
const rejectedDuplicate = new Counter('business_order_rejected_duplicate');
const rejectedOther = new Counter('business_order_rejected_other');
const malformed = new Counter('business_response_malformed');
const businessLatency = new Trend('business_order_latency');

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    flash_sale_orders: {
      executor: 'shared-iterations',
      vus,
      iterations,
      maxDuration,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: [`p(95)<${p95ThresholdMs}`, `p(99)<${p99ThresholdMs}`],
    checks: ['rate>0.99'],
  },
};

export function setup() {
  if (tokens.length === 0) {
    throw new Error(`Token file is empty: ${tokenFile}`);
  }
  if (iterations > tokens.length) {
    console.warn(
      `ITERATIONS (${iterations}) is greater than token count (${tokens.length}); duplicate-order rejections are expected.`
    );
  }
  return { startedAt: new Date().toISOString() };
}

export default function () {
  const tokenIndex = exec.scenario.iterationInTest % tokens.length;
  const user = tokens[tokenIndex];
  const res = http.post(`${baseUrl}/flash-sales/${offerId}/orders`, null, {
    headers: {
      Authorization: `Bearer ${user.token}`,
    },
    tags: {
      endpoint: 'flash-sale-order',
    },
  });

  businessLatency.add(res.timings.duration);

  const basicOk = check(res, {
    'http status is 200': (r) => r.status === 200,
    'response is json-ish': (r) => {
      try {
        const body = r.json();
        return typeof body.success === 'boolean';
      } catch (e) {
        return false;
      }
    },
  });

  if (!basicOk) {
    malformed.add(1);
    return;
  }

  const body = res.json();
  if (body.success === true) {
    accepted.add(1);
    return;
  }

  const errorMsg = body.errorMsg || '';
  if (errorMsg.includes('Insufficient stock') || errorMsg.includes('库存不足')) {
    rejectedStock.add(1);
  } else if (errorMsg.includes('Duplicate order')) {
    rejectedDuplicate.add(1);
  } else {
    rejectedOther.add(1);
    console.warn(`Unexpected business rejection: ${errorMsg}`);
  }
}

export function handleSummary(data) {
  return {
    stdout: textSummary(data),
    'load-tests/out/k6-summary.json': JSON.stringify(data, null, 2),
  };
}

function textSummary(data) {
  const acceptedCount = valueOf(data, 'business_order_accepted', 'count');
  const stockRejected = valueOf(data, 'business_order_rejected_stock', 'count');
  const duplicateRejected = valueOf(data, 'business_order_rejected_duplicate', 'count');
  const otherRejected = valueOf(data, 'business_order_rejected_other', 'count');
  const p95 = valueOf(data, 'http_req_duration', 'p(95)');
  const p99 = valueOf(data, 'http_req_duration', 'p(99)');

  return [
    '',
    'Flash sale load test summary',
    '----------------------------',
    `accepted orders:         ${acceptedCount}`,
    `stock rejections:        ${stockRejected}`,
    `duplicate rejections:    ${duplicateRejected}`,
    `other rejections:        ${otherRejected}`,
    `http duration p95:       ${p95} ms`,
    `http duration p99:       ${p99} ms`,
    '',
    'Detailed raw summary was written to load-tests/out/k6-summary.json',
    '',
  ].join('\n');
}

function valueOf(data, metricName, field) {
  const metric = data.metrics[metricName];
  if (!metric || !metric.values) {
    return 0;
  }
  const value = metric.values[field];
  if (typeof value !== 'number') {
    return 0;
  }
  return Number.isInteger(value) ? value : value.toFixed(2);
}

function openFirstExistingTokenFile(path) {
  const candidates = path.startsWith('/')
    ? [path]
    : [path, `../${path}`, `../../${path}`];
  const errors = [];
  for (const candidate of candidates) {
    try {
      return open(candidate);
    } catch (error) {
      errors.push(`${candidate}: ${error.message}`);
    }
  }
  throw new Error(`Could not open token file. Tried: ${errors.join('; ')}`);
}
