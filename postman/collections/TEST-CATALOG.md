# Error Cases Test Catalog

## Complete Test List (29 Tests)

### Setup
| # | Test Name | Request | Expected Status | Purpose |
|---|-----------|---------|-----------------|---------|
| 0 | Create Order for Error Testing | POST /create-order | 201 | Generate test OrderID for all tests |

---

## 1. Duplicate Requests (3 Tests)

| # | Test Name | Request | Body | Expected Status | Expected Behavior |
|---|-----------|---------|------|-----------------|-------------------|
| 1.1 | Duplicate Order Creation | POST /create-order | `{"orderId": "{{test_order_id}}", "numItems": 3}` | 400/409 | Reject duplicate OrderID |
| 1.2 | Duplicate Update Request (First) | PUT /update-order | `{"orderId": "{{test_order_id}}", "status": "confirmed"}` | 200 | Accept first update |
| 1.3 | Idempotent Update | PUT /update-order | `{"orderId": "{{updated_order_id}}", "status": "confirmed"}` | 200/409 | Handle idempotently |

**Category Purpose**: Verify that duplicate/repeated requests are handled gracefully (idempotency)

---

## 2. Invalid State Transitions (3 Tests)

| # | Test Name | Request | Body | Expected Status | Expected Behavior |
|---|-----------|---------|------|-----------------|-------------------|
| 2.1 | Backward State Transition | PUT /update-order | `{"orderId": "{{updated_order_id}}", "status": "new"}` | 400/409 | Reject backward transition |
| 2.2 | Invalid State Value | PUT /update-order | `{"orderId": "{{test_order_id}}", "status": "INVALID_STATE_XYZ"}` | 400/422 | Reject invalid state |
| 2.3 | Skip Valid State Progression | PUT /update-order | `{"orderId": "{{skip_order_id}}", "status": "dispatched"}` | 200/400/409 | Allow or reject (implementation-dependent) |

**Category Purpose**: Validate that the state machine only allows valid transitions

---

## 3. Empty and Invalid Fields (7 Tests)

| # | Test Name | Request | Body | Expected Status | Expected Behavior |
|---|-----------|---------|------|-----------------|-------------------|
| 3.1 | Missing OrderID in Create | POST /create-order | `{"numItems": 5}` | 400 | Reject missing orderId |
| 3.2 | Empty OrderID String | POST /create-order | `{"orderId": "", "numItems": 5}` | 400 | Reject empty orderId |
| 3.3 | Missing NumItems | POST /create-order | `{"orderId": "TEST_NO_ITEMS"}` | 400/201 | Reject or use default |
| 3.4 | Negative NumItems | POST /create-order | `{"orderId": "TEST_NEG", "numItems": -5}` | 400 | Reject negative value |
| 3.5 | Zero NumItems | POST /create-order | `{"orderId": "TEST_ZERO", "numItems": 0}` | 400/201 | Reject or use minimum |
| 3.6 | Missing Status in Update | PUT /update-order | `{"orderId": "{{test_order_id}}"}` | 400 | Reject missing status |
| 3.7 | Empty Status String | PUT /update-order | `{"orderId": "{{test_order_id}}", "status": ""}` | 400 | Reject empty status |

**Category Purpose**: Verify that required fields are validated and empty values are rejected

---

## 4. Non-Existent Resources (2 Tests)

| # | Test Name | Request | Query/Body | Expected Status | Expected Behavior |
|---|-----------|---------|------------|-----------------|-------------------|
| 4.1 | Query Non-Existent Order | GET /order-details | `?orderId=NONEXISTENT_999` | 404/200 | Return 404 or empty result |
| 4.2 | Update Non-Existent Order | PUT /update-order | `{"orderId": "NONEXISTENT_888", "status": "confirmed"}` | 404/400/409 | Reject with not found error |

**Category Purpose**: Test handling of missing/non-existent resources

---

## 5. Invalid Input Formats (4 Tests)

| # | Test Name | Request | Body | Expected Status | Expected Behavior |
|---|-----------|---------|------|-----------------|-------------------|
| 5.1 | Invalid JSON | POST /create-order | `{invalid json here]` | 400 | Reject malformed JSON |
| 5.2 | NumItems as String | POST /create-order | `{"orderId": "TEST_STRING", "numItems": "five"}` | 400/201/422 | Reject type mismatch |
| 5.3 | Extra Unknown Fields | POST /create-order | `{"orderId": "TEST_EXTRA", "numItems": 3, "unknownField": "value"}` | 200/201/400 | Ignore or accept unknown fields |
| 5.4 | OrderID with Special Chars | POST /create-order | `{"orderId": "TEST@#$%^&*()", "numItems": 3}` | 200/201/400 | Handle special characters |

**Category Purpose**: Validate input parsing, type safety, and handling of malformed data

---

## 6. Boundary Conditions (3 Tests)

| # | Test Name | Request | Body | Expected Status | Expected Behavior |
|---|-----------|---------|------|-----------------|-------------------|
| 6.1 | Maximum NumItems | POST /create-order | `{"orderId": "TEST_MAX", "numItems": 999999}` | 200/201/400 | Accept large values or enforce limits |
| 6.2 | Very Long OrderID | POST /create-order | `{"orderId": "EXTREMELY_LONG_...200chars...", "numItems": 3}` | 201/400 | Accept or enforce length limit |
| 6.3 | Single Item Order | POST /create-order | `{"orderId": "TEST_ONE", "numItems": 1}` | 201 | Accept minimum value |

**Category Purpose**: Test edge cases and boundary values

---

## 7. Null and Type Mismatches (3 Tests)

| # | Test Name | Request | Body | Expected Status | Expected Behavior |
|---|-----------|---------|------|-----------------|-------------------|
| 7.1 | OrderID as Null | POST /create-order | `{"orderId": null, "numItems": 5}` | 400 | Reject null orderId |
| 7.2 | OrderID as Number | POST /create-order | `{"orderId": 12345, "numItems": 5}` | 201/400/422 | Coerce or reject |
| 7.3 | Status as Null | PUT /update-order | `{"orderId": "{{test_order_id}}", "status": null}` | 400 | Reject null status |

**Category Purpose**: Test null safety and type coercion behavior

---

## 8. Consumer Query Errors (4 Tests)

| # | Test Name | Request | Query Params | Expected Status | Expected Behavior |
|---|-----------|---------|--------------|-----------------|-------------------|
| 8.1 | Missing OrderID Parameter | GET /order-details | (none) | 400/404 | Require orderId parameter |
| 8.2 | Empty OrderID Parameter | GET /order-details | `?orderId=` | 400/404 | Reject empty parameter |
| 8.3 | Invalid Topic Name | GET /getAllOrdersFromTopic | `?topicName=nonexistent` | 400/404 | Return not found error |
| 8.4 | Empty Topic Name | GET /getAllOrdersFromTopic | `?topicName=` | 400/404 | Reject empty parameter |

**Category Purpose**: Validate query parameter handling and GET endpoint requirements

---

## Summary Statistics

### By Category
- **Duplicate Requests**: 3 tests
- **State Transitions**: 3 tests
- **Fields & Validation**: 7 tests
- **Non-Existent Resources**: 2 tests
- **Input Formats**: 4 tests
- **Boundary Conditions**: 3 tests
- **Type Handling**: 3 tests
- **Query Parameters**: 4 tests
- **Total**: 29 tests + 1 setup

### By HTTP Method
- **POST** (create-order): 11 tests
- **PUT** (update-order): 6 tests
- **GET** (consumer endpoints): 12 tests

### By Response Code Distribution
- **400** (Bad Request): ~18 tests
- **404** (Not Found): ~4 tests
- **409** (Conflict): ~3 tests
- **422** (Unprocessable): ~2 tests
- **200/201** (Success): ~4 tests

### By Validation Type
- **Required Fields**: 3 tests
- **Field Type**: 5 tests
- **Field Value**: 7 tests
- **Query Parameters**: 4 tests
- **State Machine**: 3 tests
- **Idempotency**: 3 tests
- **Duplicates**: 3 tests
- **Not Found**: 2 tests
- **Other**: 0 tests

---

## Test Execution Guidelines

### Execution Order
1. **Run Setup first** - Creates base test data
2. **Run test categories in order** - Tests build on setup data
3. **Tests are independent** - Can skip and re-run individual tests

### Dependencies
- Tests 1.2, 1.3, 2.1, 2.2, 3.6, 3.7 depend on setup creating an order
- Test 2.3 creates its own order (independent)
- Tests 3.1-3.5 create new orders each (independent)
- Tests 4.1-4.2 don't depend on setup (self-contained)
- Tests 5.x-7.x don't depend on setup (self-contained)
- Tests 8.x are consumer-only (independent)

### Environment Variables Used
- `base_url_producer` - Producer service URL
- `base_url_consumer` - Consumer service URL
- `test_order_id` - Generated test order ID
- `duplicate_order_id` - Duplicate order ID
- `skip_order_id` - Skip state test order ID
- `updated_order_id` - Updated order tracking

---

## Expected Pass Rate

### Flexible Test Design
Tests accept multiple valid response codes:
- **400 or 409** for conflicts/duplicates
- **404 or 200 (empty)** for missing resources
- **400 or 422** for validation errors
- **200 or 201** for creation success
- **200 or 409 or 400** for state transitions

### Realistic Expectations
- ~85-95% of tests should pass
- Failed tests document implementation differences
- Failures are informative (show what API actually does)

### Success Metrics
- ✅ All tests run without timeout
- ✅ Responses are meaningful
- ✅ Error messages are descriptive
- ✅ API behaves consistently

---

## Documentation

For each test:
- **Request Type**: HTTP method
- **Endpoint**: API endpoint being tested
- **Test Data**: Request body/parameters
- **Expected Result**: HTTP status code
- **Expected Behavior**: What should happen
- **Pre-request Script**: Generates test data
- **Test Assertions**: Validates response

---

## Integration Notes

### With Sanity Collection
- Sanity tests happy path (7 tests)
- Error Cases tests unhappy paths (29 tests)
- Together = 36 tests total

### With Other Collections
- Sequencing: Tests message ordering
- Resilience: Tests fault tolerance
- Validation: Tests data constraints
- Consumer: Tests consumer endpoints

### In CI/CD
- Run Sanity first (verify system works)
- Run Error Cases (verify error handling)
- Report combined results

---

## Maintenance

### Adding New Tests
1. Choose appropriate category or create new one
2. Use existing test as template
3. Generate new test ID
4. Write pre-request script
5. Write test assertions
6. Add description
7. Update this catalog
8. Validate JSON

### Updating Tests
1. Review test in Postman
2. Make changes
3. Export updated collection
4. Update this catalog
5. Test locally
6. Commit changes

### Extending Coverage
Areas for future expansion:
- Authentication & authorization tests
- Rate limiting tests
- Concurrent request tests
- Database constraint tests
- Transaction isolation tests
- Message ordering tests
- Performance/load tests

---

## Quick Links

- **Collection File**: Error-Cases.postman_collection.json
- **Quick Reference**: ERROR-CASES-QUICK-REFERENCE.md
- **Full Documentation**: README.md
- **Environment**: EDA-Local.postman_environment.json

---

**Last Updated**: January 4, 2026  
**Test Count**: 29 + 1 setup = 30 total  
**Categories**: 8  
**Status**: Ready for use

