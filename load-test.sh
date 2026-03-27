#!/bin/bash

# Configuration
TARGET_RPS=100
DURATION_SECONDS=30
BASE_URL="http://localhost:8080/api/v1/payments"
MERCHANT_ID="a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"

# Counters
SUCCESS=0
FAILED=0
START_TIME=$(date +%s)

echo "Starting load test: ${TARGET_RPS} RPS for ${DURATION_SECONDS} seconds"
echo "Target URL: ${BASE_URL}"
echo "-------------------------------------------"

send_payment() {
    local index=$1
    local idempotency_key="load-test-$(date +%s%N)-${index}-$$"

    local amount=$(( (RANDOM % 9900) + 100 ))
    local amount_formatted="${amount}.00"

    local http_code
    http_code=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "${BASE_URL}" \
        -H "Content-Type: application/json" \
        -d "{
            \"idempotencyKey\": \"${idempotency_key}\",
            \"merchantId\": \"${MERCHANT_ID}\",
            \"amount\": \"${amount_formatted}\",
            \"currency\": \"INR\"
        }" \
        --max-time 5)

    echo $http_code
}

export -f send_payment
export BASE_URL MERCHANT_ID

# Track results
SUCCESS=0
FAILED=0
TOTAL=0

INTERVAL=$(echo "scale=6; 1 / ${TARGET_RPS}" | bc)
END_TIME=$(( START_TIME + DURATION_SECONDS ))

echo "Sending requests..."
echo ""

while [ $(date +%s) -lt $END_TIME ]; do
    LOOP_START=$(date +%s%N)

    # Fire TARGET_RPS requests in parallel using background jobs
    for i in $(seq 1 $TARGET_RPS); do
        (
            result=$(send_payment $i)
            echo $result >> /tmp/load_test_results_$$.txt
        ) &
    done

    # Wait for all background jobs in this second to complete
    wait

    TOTAL=$(( TOTAL + TARGET_RPS ))
    ELAPSED=$(( $(date +%s) - START_TIME ))
    echo "Elapsed: ${ELAPSED}s | Sent: ${TOTAL} requests"

    # Sleep remainder of the second
    LOOP_END=$(date +%s%N)
    LOOP_DURATION=$(( (LOOP_END - LOOP_START) / 1000000 ))
    SLEEP_MS=$(( 1000 - LOOP_DURATION ))

    if [ $SLEEP_MS -gt 0 ]; then
        sleep $(echo "scale=3; ${SLEEP_MS} / 1000" | bc)
    fi
done

# Wait for any remaining background jobs
wait

echo ""
echo "-------------------------------------------"
echo "Load test complete. Analysing results..."
echo ""

# Count results
if [ -f /tmp/load_test_results_$$.txt ]; then
    SUCCESS=$(grep -c "^201$" /tmp/load_test_results_$$.txt || echo 0)
    FAILED=$(grep -cv "^201$" /tmp/load_test_results_$$.txt || echo 0)
    TOTAL_RECORDED=$(wc -l < /tmp/load_test_results_$$.txt)

    echo "Results:"
    echo "  Total sent:    ${TOTAL}"
    echo "  Total recorded: ${TOTAL_RECORDED}"
    echo "  Success (201): ${SUCCESS}"
    echo "  Failed:        ${FAILED}"
    echo ""

    # Show breakdown of non-201 responses
    if [ $FAILED -gt 0 ]; then
        echo "  Response code breakdown:"
        sort /tmp/load_test_results_$$.txt | uniq -c | sort -rn | while read count code; do
            echo "    HTTP ${code}: ${count} requests"
        done
    fi

    rm /tmp/load_test_results_$$.txt
fi

ACTUAL_DURATION=$(( $(date +%s) - START_TIME ))
ACTUAL_RPS=$(echo "scale=1; ${TOTAL} / ${ACTUAL_DURATION}" | bc)

echo ""
echo "Performance:"
echo "  Duration:   ${ACTUAL_DURATION}s"
echo "  Actual RPS: ${ACTUAL_RPS}"
echo "-------------------------------------------"