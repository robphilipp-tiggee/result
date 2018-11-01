package com.tiggee.commons.result

import spock.lang.Specification
import spock.lang.Unroll

import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Function
import java.util.function.Predicate
import java.util.function.Supplier

/**
 * Tests for the result class
 *
 * Created by rob on 4/15/16.
 */
class ResultTest extends Specification {

    Result<String> successA(final String value) {
        return Result.<String> builder().success(value).addMessage('message', 'success a1').build() as Result<String>
    }

    Result<Double> successB(final double value) {
        return Result.<Double> builder().success(value).addMessage('message', 'success b1').build() as Result<Double>
    }

    Result<String> failedA(final String messageKey, final String messageValue) {
        return Result.<String> builder().withStatus(Result.Status.FAILED).addMessage(messageKey, messageValue).build() as Result<String>
    }

    Result<Double> failedB(final String messageKey, final String messageValue) {
        return Result.<Double> builder().withStatus(Result.Status.FAILED).addMessage(messageKey, messageValue).build() as Result<Double>
    }

    @Unroll
    "values should be present for when set"() {
        expect:
        successA('success A').isPresent()
        successB(3.14159).isPresent()
        !failedA('message', 'failed B').isPresent()
        !failedB('message', 'failed B').isPresent()
    }

    @Unroll
    "values should be returned or else the alternate if the value is not present"() {
        expect:
        successA('success A').orElse('something else') == 'success A'
        successB(3.14159).orElse(2.71) == (double) 3.14159
        failedA('message', 'failed A').orElse('something else') == 'something else'
        failedB('message', 'failed B').orElse(2.71) == (double) 2.71

        successA('success A').orElseGet({ -> 'something else' } as Supplier) == 'success A'
        successB(3.14159).orElseGet({ -> 2.71 } as Supplier) == (double) 3.14159
        failedA('message', 'failed A').orElseGet({ -> 'something else' } as Supplier) == 'something else'
        failedB('message', 'failed B').orElseGet({ -> 2.71 } as Supplier) == (double) 2.71
    }

    def "the value orElse is executed even when the result is a success"() {
        setup:
        final AtomicInteger count = new AtomicInteger(0)

        expect:
        successA('success A').orElse(String.valueOf(count.incrementAndGet())) == 'success A'
        count.get() == 1
        failedA('message', 'failed A').orElse(String.valueOf(count.incrementAndGet())) == '2'
        count.get() == 2
    }

    def "the supplier in orElseGet is not called when the result is a success"() {
        setup:
        final AtomicInteger count = new AtomicInteger(0)

        expect:
        successA('success A').orElseGet({ -> String.valueOf(count.incrementAndGet()) } as Supplier) == 'success A'
        count.get() == 0
        failedA('message', 'failed A').orElseGet({ -> String.valueOf(count.incrementAndGet())
        } as Supplier) == '1'
        count.get() == 1
    }

    def "result should be based on the post-andThen when the pre-andThen result succeeded"() {
        expect:
        successA('successful A').andThen({ text -> successB(3.14159) } as Function).orElse(0) == 3.14159
    }

    def "result should be based on the failed result when the pre-andThen result failed"() {
        setup:
        final Result<Double> result = failedA('message', 'failed a1').andThen({ text -> successB(3.14159) } as Function)

        expect:
        !result.isPresent()
        result.messages() == [message: 'failed a1', status: Result.Status.FAILED] as Map
    }

    def "result should be based on the failed post-andThen result"() {
        setup:
        final Result<Double> result = successA('successful A').andThen({ text -> failedB('message', 'failed B') } as Function)

        expect:
        !result.isPresent()
        result.messages() == [message: 'failed B', status: Result.Status.FAILED] as Map
    }

    def "result should have the value mapped, but otherwise be the same as the original result"() {
        setup:
        final Result<Double> result = successA('yippie').map({ text -> 3.14159 } as Function)

        expect:
        result.value().orElse(0) == (double) 3.14159
        result.messages() == successA('anything').messages()
    }

    def "failed result should map the type even though there is no value present"() {
        setup:
        final Result<Double> result = failedA('message', 'failed A').map({ text -> 3.14159 } as Function)

        expect:
        !result.isPresent()
        result.messages() == failedA('message', 'failed A').messages()
        result.messages() != failedA('message', 'i have gone rogue').messages()
    }

    def "result filter should pass through when the filter is met"() {
        expect:
        successA('test value').filter({ value -> (value == 'test value') } as Predicate).orElse('not passed') == 'test value'
        successA('test value').filter({ value -> (value == 'not test value') } as Predicate).orElse('not passed') == 'not passed'
        failedA('message', 'failed A').filter({ value -> (value == 'failed A') } as Predicate).orElse('not passed') == 'not passed'
        failedA('message', 'failed A').filter({ value -> (value == 'failed A') } as Predicate).messages() == [message: 'failed A', status: Result.Status.FAILED] as Map
    }

    def "satisfy test"() {
        expect:
        successA('test value').satisfies({ value -> (value == 'test value') } as Predicate)
        successA('test value').satisfies({ value -> (value != 'not test value') } as Predicate)
        !successA('test value').satisfies({ value -> (value == 'not test value') } as Predicate)
    }

    def "on failure"() {
        expect:
        failedA('failed', 'failed result')
            .onFailure({ ->
            Result.<String> builder().failed('fail function called').build()
        } as Supplier)
            .messages()['error'] == 'fail function called'
    }

    def "on failure with result"() {
        expect:
        failedA('failed', 'failed result')
            .onFailure({ result ->
            Result.builder().failed('fail function called').addMessage('result', result.messages()).build()
        } as Function)
            .messages()['result'] == [failed: 'failed result', status: Result.Status.FAILED]
    }

    def "foreach"() {
        given:
        // no i'm not a retard, but [ 2, 5, 6 ].collect{ it * Math.PI } returns an array of 'null' elements....go figure?
        def expectedValues = [2 * Math.PI, 5 * Math.PI, 6 * Math.PI]

        def failOn = [3, 1, 4] as Set
        def piPatternFunction = { index ->
            if(failOn.contains(index)) {
                return Result.<Double> builder().failed('pi pattern violation').build()
            }
            else {
                return Result.<Double> builder().success(index * Math.PI as Double).build()
            }
        }
        def success = Result.<List<Integer>> builder()
            .success([2, 5, 6])
            .build()
            .foreach(piPatternFunction as Function, Integer)
        def values = success.orElse(null)
        def failure = Result.<List<Integer>> builder()
            .success([3, 2, 1, 5, 6, 4])
            .build()
            .foreach(piPatternFunction as Function, Integer)

        expect:
        success.isSuccess()
        values != null
        values == expectedValues
        !failure.isSuccess()
        (failure.messages().get('3') as Map).get('error') == 'pi pattern violation'
        (failure.messages().get('1') as Map).get('error') == 'pi pattern violation'
        (failure.messages().get('4') as Map).get('error') == 'pi pattern violation'
    }

    def "foreach fail-fast"() {
        given:
        // no i'm not a retard, but [ 2, 5, 6 ].collect{ it * Math.PI } returns an array of 'null' elements....go figure?
        def expectedValues = [2 * Math.PI, 5 * Math.PI, 6 * Math.PI]

        def failOn = [3, 1, 4] as Set
        def piPatternFunction = { index ->
            if(failOn.contains(index)) {
                return Result.<Double> builder().failed('pi pattern violation').build()
            }
            else {
                return Result.<Double> builder().success(index * Math.PI as Double).build()
            }
        }

        def success = Result.<List<Integer>> builder()
            .success([2, 5, 6])
            .build()
            .foreach(piPatternFunction as Function, Integer, true)
        def values = success.orElse(null)
        def failure = Result.<List<Integer>> builder()
            .success([3, 2, 1, 5, 6, 4])
            .build()
            .foreach(piPatternFunction as Function, Integer, true)

        expect:
        success.isSuccess()
        values != null
        values == expectedValues
        !failure.isSuccess()
        failure.messages().get('error') == 'Failed to process inputs'
        failure.messages().get('failed_on') == '3'
    }

    def "should be able apply function to each element of a map"() {
        setup:
        def testMap = [house: 3, dog: 5, bird: 10] as LinkedHashMap

        when:
        def result = Result.foreachEntry(testMap, { entry -> Result.builder().success(entry.getValue() % 2 == 0).build() })

        then:
        result.isSuccess()

        and:
        result.orElse([]) == [false, false, true]
    }

    def "should fail when on of the results of applying the function to one of the map entries fails"() {
        setup:
        def testMap = [house: 3, dog: 5, bird: 10] as LinkedHashMap

        when:
        def result = Result.foreachEntry(testMap, { entry ->
            if(entry.getValue() % 2 == 1) {
                Result.builder().success(true).build()
            }
            else {
                Result.builder().badRequest('encountered an even number').addMessage('key', entry.getKey()).build()
            }
        })

        then:
        !result.isSuccess()

        and:
        result.message('bird=10').orElse([:])['error'] == 'encountered an even number'
        result.message('bird=10').orElse([:])['key'] == 'bird'
        result.message('bird=10').orElse([:])['status'] == Result.Status.BAD_REQUEST
    }

    def "calling foreach with fast-fail on a list of integers should return a result with a list of those integers times π"() {
        given:
        def results = Result.<List<Integer>> builder().success([1, 2, 3, 5]).build()

        def processedResults = results.foreach({
            int value -> Result.<Double> builder().success(value * Math.PI).build()
        } as Function, Integer, true)
        def expected = [1 * Math.PI, 2 * Math.PI, 3 * Math.PI, 5 * Math.PI]

        expect:
        processedResults.orElse([]) == expected
    }

    def "calling foreach with fast-fail on an integer result should fail"() {
        given:
        def result = Result.<Integer> builder().success(1).build()

        def processedResult = result.foreach({
            int value -> Result.<Double> builder().success(value * Math.PI).build()
        } as Function, Integer, true)

        expect:
        processedResult.isSuccess()
        processedResult.orElse(null) == [Math.PI]
    }

    def "calling foreach without fast-fail on a list of integers should return a result with a list of those integers times π"() {
        given:
        def results = Result.<List<Integer>> builder().success([1, 2, 3, 5]).build()

        def processedResults = results.foreach({
            int value -> Result.<Double> builder().success(value * Math.PI).build()
        } as Function, Integer)
        def expected = [1 * Math.PI, 2 * Math.PI, 3 * Math.PI, 5 * Math.PI]

        expect:
        processedResults.orElse([]) == expected
    }

    def "calling foreach without fast-fail on an integer result should fail"() {
        given:
        def result = Result.<Integer> builder().success(1).build()

        def processedResult = result.foreach({
            int value -> Result.<Double> builder().success(value * Math.PI).build()
        } as Function, Integer)

        expect:
        processedResult.isSuccess()
        processedResult.orElse(null) == [Math.PI]
    }

    def "foreach should return failed if one of the function calls throws an exception"() {
        when:
        def result = Result.<List<Integer>> builder().success([1, 2, -3, 4]).build()

        and:
        def foreach = result.foreach({
            int value ->
                if(value > 0) {
                    return Result.<Integer> builder().success(value * 2).build()
                }
                else {
                    throw new Error()
                }
        } as Function, Integer)

        then:
        !foreach.isSuccess()
        !foreach.isPresent()
    }

    def "should be able to accumulate errors"() {
        given:
        def result1 = Result.<Integer> builder().success(100).build()
        def result2 = result1.andThen({ value ->
            Result.<Integer> builder()
                .failed("result 1 failed (1)")
                .addMessage("error", "result 1 failed (2)")
                .addMessage("error", "result 1 failed (3)")
                .addMessage("error", "result 1 failed (4)")
                .build()
        } as Function)

        expect:
        result2.messages()['error'] == 'result 1 failed (4)'
        result2.messages()['error_'] == 'result 1 failed (3)'
        result2.messages()['error__'] == 'result 1 failed (2)'
        result2.messages()['error___'] == 'result 1 failed (1)'
    }

    @Unroll
    "transaction where the bounded function #boundedSuccess, the commit/rollback #commitRollbackSuccess, then result #resultSuccess"() {
        given:
        def transaction = new Transaction("the transaction", true)
        def transactionResult = Result.<Transaction> builder().success(transaction).build()
        def boundedResult = Result.<String> builder().withStatus(BoundedStatus).withValue(BoundedValue).build()
        def result = transactionResult.transaction(
            { -> boundedResult } as Supplier,
            { trans -> (trans as Transaction).commit(CommitRollbackSucceeds) } as Function,
            { trans -> (trans as Transaction).rollback(CommitRollbackSucceeds) } as Function
        )

        expect:
        result.isSuccess() == ExpectedResult
        transaction.isRolledBack() == (CommitRollbackSucceeds && ShouldRollback)
        transaction.isCommitted() == (CommitRollbackSucceeds && ShouldCommit)

        where:
        BoundedStatus         | BoundedValue     | CommitRollbackSucceeds | ExpectedResult | ShouldCommit | ShouldRollback
        Result.Status.SUCCESS | 'bound function' | true                   | true           | true         | false
        Result.Status.FAILED  | null             | true                   | false          | false        | true
        Result.Status.SUCCESS | 'bound function' | false                  | false          | true         | false
        Result.Status.FAILED  | null             | false                  | false          | false        | true

        boundedSuccess = ExpectedResult ? 'succeeds' : 'fails'
        commitRollbackSuccess = CommitRollbackSucceeds ? 'succeeds' : 'fails'
        resultSuccess = ExpectedResult ? 'succeeds' : 'fails'
    }

    @Unroll
    "#newTransaction transaction where the bounded function #boundedSuccess, the commit/rollback #commitRollbackSuccess, then result #resultSuccess"() {
        given:
        def transaction = new Transaction("the transaction", NewTransaction)
        def transactionResult = Result.<Transaction> builder().success(transaction).build()
        def boundedResult = Result.<String> builder().withStatus(BoundedStatus).withValue(BoundedValue).build()
        def result = transactionResult.transaction(
            { trans -> (trans as Transaction).isNew() } as Predicate,
            { -> boundedResult } as Supplier,
            { trans -> (trans as Transaction).commit(CommitRollbackSucceeds) } as Function,
            { trans -> (trans as Transaction).rollback(CommitRollbackSucceeds) } as Function
        )

        expect:
        result.isSuccess() == ExpectedResult
        transaction.isRolledBack() == (CommitRollbackSucceeds && ShouldRollback)
        transaction.isCommitted() == (CommitRollbackSucceeds && ShouldCommit)

        where:
        BoundedStatus         | BoundedValue     | NewTransaction | CommitRollbackSucceeds | ExpectedResult | ShouldCommit | ShouldRollback
        Result.Status.SUCCESS | 'bound function' | true           | true                   | true           | true         | false
        Result.Status.FAILED  | null             | true           | true                   | false          | false        | true
        Result.Status.SUCCESS | 'bound function' | true           | false                  | false          | true         | false
        Result.Status.FAILED  | null             | true           | false                  | false          | false        | true

        Result.Status.SUCCESS | 'bound function' | false          | true                   | true           | false        | false
        Result.Status.FAILED  | null             | false          | true                   | false          | false        | false
        Result.Status.SUCCESS | 'bound function' | false          | false                  | true           | false        | false
        Result.Status.FAILED  | null             | false          | false                  | false          | false        | false

        boundedSuccess = ExpectedResult ? 'succeeds' : 'fails'
        commitRollbackSuccess = CommitRollbackSucceeds ? 'succeeds' : 'fails'
        resultSuccess = ExpectedResult ? 'succeeds' : 'fails'
        newTransaction = NewTransaction ? 'new' : 'existing'
    }

    def "transaction should roll-back when function throws exception"() {
        given:
        def transaction = new Transaction("the transaction", true)
        def transactionResult = Result.<Transaction> builder().success(transaction).build()

        def result = transactionResult.transaction(
            { trans -> (trans as Transaction).isNew() } as Predicate,
            { -> throw new Error() } as Supplier,
            { trans -> (trans as Transaction).commit(true) } as Function,
            { trans -> (trans as Transaction).rollback(true) } as Function
        )

        expect:
        !result.isSuccess()
        result.status() == Result.Status.FAILED
        result.message('error').orElse("") == "Exception thrown in supplied transaction-bounded function"
    }

    def "transaction should roll-back when the commit function throws exception"() {
        given:
        def transaction = new Transaction("the transaction", true)
        def transactionResult = Result.<Transaction> builder().success(transaction).build()

        def result = transactionResult.transaction(
            { trans -> (trans as Transaction).isNew() } as Predicate,
            { -> Result.builder().success(3).build() } as Supplier,
            { trans -> throw new Error() } as Function,
            { trans -> (trans as Transaction).rollback(true) } as Function
        )

        expect:
        !result.isSuccess()
        result.status() == Result.Status.FAILED
        result.message('error').orElse("") == "Exception thrown when attempting to commit the transaction"
    }

    def "transaction should roll-back when the rollback function throws exception"() {
        given:
        def transaction = new Transaction("the transaction", true)
        def transactionResult = Result.<Transaction> builder().success(transaction).build()

        def result = transactionResult.transaction(
            { trans -> (trans as Transaction).isNew() } as Predicate,
            { -> Result.builder().failed("failed").build() } as Supplier,
            { trans -> (trans as Transaction).commit(true) } as Function,
            { trans -> throw new Error() } as Function
        )

        expect:
        !result.isSuccess()
        result.status() == Result.Status.FAILED
        result.message('error').orElse("") == "Exception thrown when attempting to rollback the transaction, and then again on the final rollback"
    }

    def "transaction should roll-back when the commit function throws exception and so does the rollbakc"() {
        given:
        def transaction = new Transaction("the transaction", true)
        def transactionResult = Result.<Transaction> builder().success(transaction).build()

        def result = transactionResult.transaction(
            { trans -> (trans as Transaction).isNew() } as Predicate,
            { -> Result.builder().success(3).build() } as Supplier,
            { trans -> throw new Error() } as Function,
            { trans -> throw new Error() } as Function
        )

        expect:
        !result.isSuccess()
        result.status() == Result.Status.FAILED
        result.message('error').orElse("") == "Exception thrown when attempting to commit the transaction, and then again on the final rollback"
    }

    def "transaction should return a failure when the rollback fails"() {
        given:
        def transaction = new Transaction("the transaction", true)
        def transactionResult = Result.<Transaction> builder().success(transaction).build() as Result<Transaction>

        def result = transactionResult.transaction(
            { trans -> (trans as Transaction).isNew() } as Predicate,
            { -> throw new Error() } as Supplier,
            { trans -> (trans as Transaction).commit(true) } as Function,
            { trans -> (trans as Transaction).rollback(false) } as Function
        )

        expect:
        !result.isSuccess()
        result.status() == Result.Status.FAILED
    }

    def "meetsCondition should call the specified function"() {
        given:
        Result<String> test = Result.<String> builder().success('first test').build() as Result<String>

        def onSuccess = test.meetsCondition(
            { value -> value == 'first test' } as Predicate,
            { value -> Result.<Integer> builder().success(value.size()).build() } as Function,
            { ->
                Result.<Integer> builder().failed("Result was a success, but predicate did not evaluate to true").build()
            } as Supplier
        )

        expect:
        onSuccess.isSuccess()
        onSuccess.orElse(0) == 'first test'.size()
    }

    def "meetsCondition should cause failure when the call to the specified function throws an exception"() {
        given:
        Result<String> test = Result.<String> builder().success('first test').build() as Result<String>

        def onSuccess = test.meetsCondition(
            { value -> value == 'first test' } as Predicate,
            { value -> throw new Error() } as Function,
            { ->
                Result.<Integer> builder()
                    .failed("Result was a success, but predicate did not evaluate to true")
                    .build()
            } as Supplier
        )

        expect:
        !onSuccess.isSuccess()
        !onSuccess.isPresent()
    }

    def "onSuccess should not be called when the predicate is not met"() {
        given:
        Result<String> test = Result.<String> builder().success('first test').build() as Result<String>


        def onSuccess = test.meetsCondition(
            { value -> value == 'not the same, so this isn\'t called' } as Predicate,
            { value -> Result.<Integer> builder().success(value.size()).build() } as Function,
            { ->
                Result.<Integer> builder().failed("Result was a success, but predicate did not evaluate to true").build()
            } as Supplier
        )

        expect:
        !onSuccess.isSuccess()
        !onSuccess.isPresent()
        onSuccess.messages()['error'] == 'Result was a success, but predicate did not evaluate to true'
    }

    def "onSuccess should not be called when the calling result is not a success"() {
        given:
        Result<String> test = Result.<String> builder().failed("Failed, bitches").build() as Result<String>


        def onSuccess = test.meetsCondition(
            { value -> value == 'not the same, so this isn\'t called' } as Predicate,
            { value -> Result.<Integer> builder().success(value.size()).build() } as Function,
            { ->
                Result.<Integer> builder()
                    .failed("Result was a success, but predicate did not evaluate to true")
                    .build()
            } as Supplier
        )

        expect:
        !onSuccess.isSuccess()
        !onSuccess.isPresent()
        test.messages()['error'] == "Failed, bitches"
    }

    def "andThen should return the result produced by the success function"() {
        when:
        def success = Result.<Integer> builder().success(314).build()
        def andThen = success.andThen(
            { value -> Result.<String> builder().success("π").build() },
            { result -> Result.<String> builder().failed("no π").build() }
        )

        then:
        andThen.isSuccess()
        andThen.value().orElse('') == 'π'
    }

    def "andThen should return a failure if the specified success function throws an exception when called"() {
        when:
        def success = Result.<Integer> builder().success(314).build()
        def andThen = success.andThen(
            { value -> throw new Error() },
            { result -> Result.<String> builder().failed("no π").build() }
        )

        then:
        !andThen.isSuccess()
        !andThen.isPresent()
    }

    def "andThen should return a failure if the specified failure function throws an exception when called"() {
        when:
        def success = Result.<Integer> builder().failed("result failed").build()
        def andThen = success.andThen(
            { value -> Result.<String> builder().success("π").build() },
            { value -> throw new Error() },
        )

        then:
        !andThen.isSuccess()
        !andThen.isPresent()
    }

    def "andThen should return a success even if the specified failure function throws an exception when called"() {
        when:
        def success = Result.<Integer> builder().success(314).build()
        def andThen = success.andThen(
            { value -> Result.<String> builder().success("π").build() },
            { value -> throw new Error() },
        )

        then:
        andThen.isSuccess()
        andThen.orElse('') == 'π'
    }

    def "andThen should return the result produced by the failure function"() {
        when:
        def success = Result.<Integer> builder().failed("got π").build()
        def andThen = success.andThen(
            { value -> Result.<String> builder().success("π").build() },
            { result -> Result.<String> builder().failed("no π").build() }
        )

        then:
        !andThen.isSuccess()
        andThen.messages()['error'] == 'no π'
    }

    def "map should return an empty value when the specified mapping function throws an exception"() {
        when:
        def success = Result.<Integer> builder().success(314).build()
        def map = success.map({ value -> throw new Error() })

        then: "the status should be reported as success"
        map.status() == Result.Status.SUCCESS

        and: "the result is no longer a success, because it has no value"
        !map.isSuccess()
        !map.isPresent()
    }

    def "meets condition keeping original result on meets predicate"() {
        expect:
        Result.builder().success(10).build()
            .meetsCondition(
            {number -> number == 10} as Predicate,
            {number -> Result.builder().success("yep - $number").build()} as Function,
            { -> Result.builder().success('nope').build()} as Supplier
        ).orElse('') == 'yep - 10'

        and:
        Result.builder().success(10).build()
            .meetsCondition(
            {number -> number != 10} as Predicate,
            {number -> Result.builder().success("yep - $number").build()} as Function,
            { -> Result.builder().success('nope').build()} as Supplier
        ).orElse('') == 'nope'
    }

    def "meets condition ignoring original result"() {
        expect:
        Result.builder().success(10).build()
            .meetsCondition(
            {number -> number == 10} as Predicate,
            { -> Result.builder().success('yep').build()} as Supplier,
            { -> Result.builder().success('nope').build()} as Supplier
        ).orElse('') == 'yep'

        and:
        Result.builder().success(10).build()
            .meetsCondition(
            {number -> number != 10} as Predicate,
            {number -> Result.builder().success('yep').build()} as Supplier,
            { -> Result.builder().success('nope').build()} as Supplier
        ).orElse('') == 'nope'
    }

    def "meets condition keeping original result"() {
        expect:
        Result.builder().success(10).build()
            .meetsCondition(
            {number -> number == 10} as Predicate,
            {number -> Result.builder().success("yep - $number").build()} as Function,
            {number -> Result.builder().success("nope - $number").build()} as Function
        ).orElse('') == 'yep - 10'

        and:
        Result.builder().success(10).build()
            .meetsCondition(
            {number -> number != 10} as Predicate,
            {number -> Result.builder().success("yep - $number").build()} as Function,
            {number -> Result.builder().success("nope - $number").build()} as Function
        ).orElse('') == 'nope - 10'
    }

    def "meets condition keeping original result when predicate is not met"() {
        expect:
        Result.builder().success(10).build()
            .meetsCondition(
            {number -> number == 10} as Predicate,
            { -> Result.builder().success("yep").build()} as Supplier,
            {number -> Result.builder().success("nope - $number").build()} as Function
        ).orElse('') == 'yep'

        and:
        Result.builder().success(10).build()
            .meetsCondition(
            {number -> number != 10} as Predicate,
            { -> Result.builder().success("yep").build()} as Supplier,
            {number -> Result.builder().success("nope - $number").build()} as Function
        ).orElse('') == 'nope - 10'
    }

    def "consume on success"() {
        when:
        def container = []
        def result = Result.builder().success(10).build().onSuccess({number -> container.add(number * 2)})

        then: 'the result holds the original value'
        result.isSuccess()
        result.orElse(0) == 10

        and: 'the container has been updated'
        container == [20]
    }

    def "consume based on whether the result succeeded or not"() {
        when:
        def container = []
        Result.builder().success(10).build()
            .ifResult({number -> container.add(number)}, {number -> container.add(-1)})

        then:
        container == [10]

        when:
        Result.builder().failed('damn, the result failed').build()
            .ifResult({number -> container.add(number)}, {number -> container.add(-1)})

        then:
        container == [10, -1]
    }

    def "or else get the value from the function"() {
        expect:
        Result.builder().failed('damn, failed again').build().orElse(10) == 10
        Result.builder().success(Math.PI).build().orElse(10) == Math.PI

        and:
        Result.builder().failed('damn, failed again').build()
            .orElseGet({result -> result.message('error').orElse('') + 10} as Function) == 'damn, failed again10'
        Result.builder().success(Math.PI).build().orElseGet({result -> 10} as Function) == Math.PI
    }

    def "throw on failure"() {
        when:
        Result.builder().failed('throw it').build().orElseThrow({ -> new IllegalStateException('test')} as Supplier)

        then:
        thrown(IllegalStateException)

        when:
        def result = Result.builder().success('do not throw it').build().orElseThrow({ -> new IllegalStateException('test')} as Supplier)

        then:
        result == 'do not throw it'
    }

    def "throw on failure with the original result"() {
        when:
        Result.builder().failed('throw it').build().orElseThrow({result -> new IllegalStateException("test: ${result.message('error').orElse('')}")} as Function)

        then:
        def exception = thrown(IllegalStateException)
        exception.getMessage() == 'test: throw it'

        when:
        def result = Result.builder().success('do not throw it').build().orElseThrow({result -> new IllegalStateException('test')} as Function)

        then:
        result == 'do not throw it'
    }

    def "not found result"() {
        expect:
        Result.builder().success('yeah').build().status() == Result.Status.SUCCESS
        Result.builder().notFound('where is it').build().status() == Result.Status.NOT_FOUND
        Result.builder().indeterminant('hmm, which is it').build().status() == Result.Status.INDETERMINANT
        Result.builder().badRequest('what did i do wrong?').build().status() == Result.Status.BAD_REQUEST
        Result.builder().failed("i'm just not good enough").build().status() == Result.Status.FAILED
        Result.builder().connectionFailed("can't connect?").build().status() == Result.Status.CONNECTION_FAILED
    }

    class Transaction {
        private final boolean isNewTransaction
        private final String transactionId
        private boolean committed = false
        private boolean rolledBack = false

        Transaction(final String id, final boolean isNew) {
            this.transactionId = id
            this.isNewTransaction = isNew
        }

        boolean isNew() {
            return isNewTransaction
        }

        Result<Boolean> commit(final boolean willSucceed) {
            committed = willSucceed
            return Result.<Boolean> builder()
                .withStatus(willSucceed ? Result.Status.SUCCESS : Result.Status.FAILED)
                .withValue(willSucceed ? willSucceed : null)
                .build() as Result<Boolean>
        }

        Result<Boolean> rollback(final boolean willSucceed) {
            rolledBack = willSucceed
            return Result.<Boolean> builder()
                .withStatus(willSucceed ? Result.Status.SUCCESS : Result.Status.FAILED)
                .withValue(willSucceed ? willSucceed : null)
                .build() as Result<Boolean>
        }

        boolean isCommitted() {
            return committed
        }

        boolean isRolledBack() {
            return rolledBack
        }
    }
}
