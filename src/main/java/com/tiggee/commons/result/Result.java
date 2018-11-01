package com.tiggee.commons.result;

import com.google.common.base.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.util.Collections.emptyList;

/**
 * Immutable.
 * Result object to handle the values and error messages from the repositories and services. Typical usage is:
 * <ul>
 * <li>If the operation succeeds, set the value, and set the status to {@link Status#SUCCESS}. The messages can be empty.</li>
 * <li>if the operation fails, then set the status to {@link Status#BAD_REQUEST} or {@link Status#NOT_FOUND} and
 * add messages to describe the problem. One of the messages should have a name "error", and a value that describes
 * the reason why the operation failed. The other messages should be the field names and values that help identify
 * the conditions surrounding the failed operation.</li>
 * </ul>
 * <p>
 * Created by rob on 3/30/16.
 */
@SuppressWarnings("WeakerAccess")
public class Result<T> {
	private static final Logger LOGGER = LoggerFactory.getLogger(Result.class);

	public static final String STATUS = "status";

	@Nullable
	private final T value;
	private final Map<String, Object> messages;

	/**
	 * @param value The (optional) value of the result (expected when the operation is a success and returns a value)
	 * @param messages The name-value messages that describe the problem (expected when an operation fails)
	 */
	private Result(@Nullable final T value, final Map<String, Object> messages) {
		this.value = value;
		this.messages = messages;
	}

	/**
	 * @param <V> The type of the value
	 * @return The builder for constructing a valid result
	 */
	public static <V> Builder<V> builder() {
		return new Builder<>();
	}

	/**
	 * @return The value of the result (i.e. the value that the operation returns if it is successful)
	 */
	public Optional<T> value() {
		return Optional.ofNullable(value);
	}

	/**
	 * @return The status of the operation
	 */
	public Status status() {
		return (Status) messages.get(STATUS);
	}

	/**
	 * @return the name-value pairs comprising the message
	 */
	public Map<String, Object> messages() {
		return Collections.unmodifiableMap(messages);
	}

	/**
	 * @param key The key for the message to retrieve
	 * @return An optional holding the message, if it exists, otherwise, an empty optional
	 */
	public Optional<Object> message(final String key) {
		return Optional.ofNullable(messages.get(key));
	}

	/**
	 * Filters the result based on the predicate
	 * @param predicate The predicate that filters the result
	 * @return This result if no value is present or the result value passed to the predicate; otherwise a copy of this
	 * result, but with the value removed (effectively an empty result)
	 */
	public Result<T> filter(final Predicate<? super T> predicate) {
		Objects.requireNonNull(predicate);
		if(!isPresent()) {
			return this;
		}
		else {
			return predicate.test(value) ? this : Result.<T>builder().withStatus(status()).addMessages(messages).build();
		}
	}

	/**
	 * Converts the result's value using the specified value-conversion function
	 * @param valueConversionFunction The value conversion function
	 * @param <V> The type parameter of the new result
	 * @return The result with its value mapped to the new value
	 */
	public <V> Result<V> map(final Function<T, V> valueConversionFunction) {
		return value()
				.map(value -> callFunction(value, valueConversionFunction))
				.orElseGet(() -> Result.<V>builder().withStatus(status()).addMessages(messages()).build());
	}

	/**
	 * Calls the specified function and wraps the mapped value into this result. If the call to the function throws an
	 * exception, then returns a failure result
	 * @param value The value to pass into the specified function
	 * @param function The specified function
	 * @param <R> The type parameter of the value held in the returned result
	 * @return A result that is a copy of this result, except that it has the mapped value, or an empty value if the specified
	 * function throws an exception
	 */
	private <R> Result<R> callFunction(final T value, final Function<T, R> function) {
		try {
			final R returnedValue = function.apply(value);
			return Result.<R>builder().success(returnedValue).addMessages(messages).build();
		}
		catch(Throwable e) {
			return Result.<R>builder()
					.withStatus(status())
					.addMessage("exception", e.getMessage())
					.addMessages(messages())
					.build();
		}
	}

	/**
	 * Allows a list of inputs to be handed to a function, that returns a {@link Result}, and have all those results
	 * combined into a single result. If all the function calls succeed, then the returned result is a success. If any
	 * of the function calls return a failure, then processing of the input stops, and the overall result is a failure.
	 * <p>
	 * For a version of this method that does NOT fail-fast, see {@link #foreach(List, Function)}
	 * @param function The function to call as a batch
	 * @param elementClass The type of the list element of this {@link Result}'s value
	 * @param <V> The return type of the specified function, and therefore, of the overall result
	 * @param <E> The element of the list
	 * @return The list of values from calling the function for each of the inputs, or a failure if any of the function
	 * calls fail
	 *
	 * @see #foreach(List, Function)
	 */
	public <V, E> Result<List<V>> foreach(final Function<E, Result<V>> function, final Class<E> elementClass) {
		return foreach(function, elementClass, false);
	}

	/**
	 * Allows a list of inputs to be handed to a function, that returns a {@link Result}, and have all those results
	 * combined into a single result. If all the function calls succeed, then the returned result is a success. If any
	 * of the function calls return a failure, then processing of the input stops, and the overall result is a failure.
	 * <p>
	 * For a version of this method that does NOT fail-fast, see {@link #foreach(List, Function)}
	 * @param function The function to call as a batch
	 * @param elementClass The type of the list element of this {@link Result}'s value
	 * @param failFast Set to `true` if processing halts immediately on a failure; or `false` if the processing is exhaustive
	 * @param <V> The return type of the specified function, and therefore, of the overall result
	 * @param <E> The element of the list
	 * @return The list of values from calling the function for each of the inputs, or a failure if any of the function
	 * calls fail
	 *
	 * @see #foreach(Function, Class)
	 */
	public <V, E> Result<List<V>> foreach(final Function<E, Result<V>> function,
										  final Class<E> elementClass,
										  final boolean failFast) {
		return foreachResult(function, elementClass, failFast ? Result::foreachFailFast : Result::foreach);
	}

	/**
	 * For results that have a list value, passes the list to the specified `foreachFunction` which applies the specified function
	 * to each element of the list and returns a result with a list.
	 * <p>
	 * If the result is not a list, then applies the specified function to the single element and returns a result with
	 * a single-element list
	 * @param function The function to which to pass the input to get a {@link Result}
	 * @param elementClass The class of the element
	 * @param foreachFunction The for-each function implementation (i.e. fail-fast, exhaustive)
	 * @param <V> The value type of the resultant {@link Result}
	 * @param <E> The type of the list element
	 * @return A result with a list of values if ALL the calls were successful, or else a failure
	 */
	private <V, E> Result<List<V>> foreachResult(final Function<E, Result<V>> function,
												 final Class<E> elementClass,
												 final BiFunction<List<E>, Function<E, Result<V>>, Result<List<V>>> foreachFunction) {
		return value()
				.map(values -> {
					if(values instanceof List) {
						final List<E> collection = ((Collection<?>) values).stream().map(elementClass::cast).collect(Collectors.toList());
						return foreachFunction.apply(collection, function);
					}
					else {
						return function.apply(elementClass.cast(values)).map(Collections::singletonList);
					}
				})
				.orElseGet(() -> Result.<List<V>>builder().withStatus(status()).addMessages(messages()).build());
	}

	/**
	 * Allows a list of inputs to be handed to a function, that returns a {@link Result}, and have all those results
	 * combined into a single result. If all the function calls succeed, then the returned result is a success. If any
	 * of the function calls return a failure, then the overall result is also a failure. This method calls the specified
	 * function on each input until the inputs are exhausted, regardless of failures.
	 * <p>
	 * For a version of this method that fails-fast, see {@link #foreachFailFast(Collection, Function)}
	 * @param inputs The list of inputs to pass to the specified function
	 * @param function The function to call as a batch
	 * @param <V> The return type of the specified function, and therefore, of the overall result
	 * @param <T> The input type to the function
	 * @return The list of values from calling the function for each of the inputs, or a failure if any of the function
	 * calls fail
	 *
	 * @see #foreachFailFast(Collection, Function)
	 */
	public static <V, T> Result<List<V>> foreach(final List<T> inputs, final Function<T, Result<V>> function) {
		// return a successful result with an empty list when the input list is empty
		if(inputs.isEmpty()) {
			return Result.<List<V>>builder().success(emptyList()).build();
		}

		// call the specified function on each of the elements in the list
		final List<Result<V>> results = inputs.stream()
				.map(value -> callResultFunction(value, function))
				.collect(Collectors.toList());

		if(results.stream().filter(Result::isSuccess).count() == inputs.size()) {
			final List<V> values = results.stream()
					.map(result -> result.value().orElse(null))
					.collect(Collectors.toList());
			return Result.<List<V>>builder().withStatus(Status.SUCCESS).withValue(values).build();
		}
		else {
			// grab all the error messages from each failure
			final Map<String, Map<String, Object>> messages = IntStream.range(0, results.size())
					.filter(index -> !results.get(index).isSuccess())
					.boxed()
					.collect(Collectors.toMap(index -> inputs.get(index).toString(), index -> results.get(index).messages()));
			return Result.<List<V>>builder().withStatus(Status.FAILED).addMessages(messages).build();
		}
	}

	/**
	 * Allows a list of inputs to be handed to a function, that returns a {@link Result}, and have all those results
	 * combined into a single result. If all the function calls succeed, then the returned result is a success. If any
	 * of the function calls return a failure, then processing of the input stops, and the overall result is a failure.
	 * <p>
	 * For a version of this method that does NOT fail-fast, see {@link #foreach(List, Function)}
	 * @param inputs The list of inputs to pass to the specified function.
	 * @param function The function to call as a batch
	 * @param <V> The return type of the specified function, and therefore, of the overall result
	 * @param <T> The input type to the function
	 * @return The list of values from calling the function for each of the inputs, or a failure if any of the function
	 * calls fail
	 *
	 * @see #foreach(List, Function)
	 */
	public static <V, T> Result<List<V>> foreachFailFast(final Collection<T> inputs, final Function<T, Result<V>> function) {
		final List<V> values = new ArrayList<>();
		for(final T input : inputs) {
			final Result<V> result = callResultFunction(input, function);
			result.ifPresent(values::add);
			if(!result.isSuccess()) {
				return Result.<List<V>>builder()
						.failed("Failed to process inputs")
						.addMessage("failed_on", input == null ? "[null]" : input.toString())
						.build();
			}
		}
		return Result.<List<V>>builder().success(values).build();
	}

	/**
	 * Allows a map of entries to be handed to a function, that returns a {@link Result}, and have all those results
	 * combined into a single result. If all the function calls succeed, then the returned result is a success. If any
	 * of the function calls return a failure, then processing of the input stops, and the overall result is a failure.
	 * @param inputs The map to pass to the specified function.
	 * @param function The function to call as a batch
	 * @param <R> The type for value of the returned result (if successful)
	 * @param <K> The type for the key in the map
	 * @param <V> The type for the value in the map
	 * @return A list of results or a failure
	 */
	public static <R, K, V> Result<List<R>> foreachEntry(final Map<K, V> inputs, final Function<Map.Entry<K, V>, Result<R>> function) {
		if(inputs.isEmpty()) {
			return Result.<List<R>>builder().success(emptyList()).build();
		}

		return foreach(new ArrayList<>(inputs.entrySet()), function);
	}

	/**
	 * Calls the specified function on the result and returns the result whose value has been mapped. If the supplied
	 * function throws an exception, then a failure is returned
	 * @param function The function that maps the result value and returns a new result with the mapped value
	 * @param <V> The class type of the new value
	 * @return A result with the mapped value
	 */
	public <V> Result<V> andThen(final Function<T, Result<V>> function) {
		return value()
				.map(value -> callResultFunction(value, function))
				.orElseGet(() -> Result.<V>builder().withStatus(status()).addMessages(messages()).build());
	}

	/**
	 * Calls the specified success function if this result is a success, otherwise calls the specified failure function.
	 * @param successFunction The success function (accepts the value of this result, and returns a new result)
	 * @param failureFunction The failure function (accepts this result, and returns a new result that holds the failure messages)
	 * @param <R> The type of the returned result
	 * @return A result that is either produced by the success-function or the failure-function
	 */
	public <R> Result<R> andThen(final Function<T, Result<R>> successFunction,
								 final Function<Result<T>, Result<R>> failureFunction) {
		if(status() == Status.SUCCESS) {
			return andThen(successFunction);
		}
		else {
			try {
				return failureFunction.apply(this);
			}
			catch(Throwable e) {
				return Result.<R>builder()
						.failed("Exception thrown in function supplied to Result.andThen(success, failure)")
						.addMessage("exception", e.getMessage())
						.addMessages(this.messages())
						.build();
			}
		}
	}

	/**
	 * Transactional.
	 * <p>
	 * Wraps the supplied method in a {@code try{ ... } catch( Throwable e ) { ... } } block. If the supplied function
	 * throws an {@link Exception} or {@link Error}, then attempts to roll-back the transaction and returns a failure result.
	 * <p>
	 * When a transaction has been started and is the value of this result, this result uses that transaction to determine
	 * how to proceed after it has called the specified operations. If the specified operations are a success, calls
	 * the specified commit function. If any of the operations fails, then calls the rollback.
	 * <p>
	 * This {@link Result}, on which this is called should return some sort of transaction that can be used to perform
	 * the commit or rollback. As far as this method is concerned, as long as the value type of this operation's result
	 * matches the input to the commit and rollback functions, it will proceed.
	 * <p>
	 * For a version of this function that is only transactional for new transactions, see
	 * {@link #transaction(Predicate, Supplier, Function, Function)}
	 * @param boundedFunction The operation that is to be bounded by the transaction
	 * @param commitFunction The function to call if the operation succeeds (i.e. the commit on the transaction)
	 * @param rollbackFunction The function to call if the operation fails (i.e. the roll-back on the transaction)
	 * @param <V> The type parameter of result returned by the specified operation
	 * @return The value of the result if it succeeds, otherwise a failure. Also returns a failure if the commit or
	 * roll-back functions fail
	 */
	public <V> Result<V> transaction(final Supplier<Result<V>> boundedFunction,
									 final Function<T, Result<Boolean>> commitFunction,
									 final Function<T, Result<Boolean>> rollbackFunction) {
		return value()
				.map(transaction ->
				{
					final Result<V> result = callResultSupplier(boundedFunction);
					try {
						if(result.isSuccess()) {
							return commitFunction.apply(transaction).andThen(success -> result);
						}
						else {
							return rollbackFunction.apply(transaction).andThen(success -> result);
						}
					}
					catch(Throwable e) {
						return handleTransactionException(result, rollbackFunction, transaction, e.getMessage());
					}
				})
				.orElseGet(() -> Result.<V>builder()
						.withStatus(status())
						.addMessages(messages())
						.build()
				);
	}

	/**
	 * Transactional when the transaction meets the specified condition; otherwise, not transactional.
	 * <p>
	 * Wraps the supplied method in a {@code try{ ... } catch( Throwable e ) { ... } } block. If the supplied function
	 * throws an {@link Exception} or {@link Error}, then returns a failure result. In cases where the supplied function
	 * is transactional (i.e. meets the specified condition), then attempts to roll-back the transaction.
	 * <p>
	 * When a transaction has been started and is the value of this result, this result uses that transaction to determine
	 * how to proceed after it has called the specified operations.
	 * <ul>
	 * <li>If the transaction is a new transaction based on the specified predicate, then if the operations are a
	 * success, calls the specified commit function. If any of the operations fails, then calls the rollback</li>
	 * <Li>If the transaction is part of a existing transaction, then it does NOT call the commit or roll-back
	 * functions, regardless of the outcome. Rather, behaves as a non-transactional set of operations.</Li>
	 * </ul>
	 * <p>
	 * This {@link Result}, on which this is called should return some sort of transaction that can be used to perform
	 * the commit or rollback. As far as this method is concerned, as long as the value type of this operation's result
	 * matches the input to the commit and rollback functions, it will proceed.
	 * <p>
	 * For a version of this function that is always transactional, see {@link #transaction(Supplier, Function, Function)}
	 * @param transactional Predicate that reports if the bounded operation should be transactional
	 * @param boundedFunction The operation that is to be bounded by the transaction
	 * @param commitFunction The function to call if the operation succeeds (i.e. the commit on the transaction)
	 * @param rollbackFunction The function to call if the operation fails (i.e. the roll-back on the transaction)
	 * @param <V> The type parameter of result returned by the specified operation
	 * @return The value of the result if it succeeds, otherwise a failure. Also returns a failure if the commit or
	 * roll-back functions fail
	 */
	public <V> Result<V> transaction(final Predicate<T> transactional,
									 final Supplier<Result<V>> boundedFunction,
									 final Function<T, Result<Boolean>> commitFunction,
									 final Function<T, Result<Boolean>> rollbackFunction) {
		return value()
				.map(transaction ->
				{
					Result<V> result = null;
					try {
						result = boundedFunction.get();
						final Result<V> finalResult = result;

						final boolean isTransactional = transactional.test(transaction);
						if(isTransactional && result.isSuccess()) {
							// transactional and a success, so commit
							return commitFunction.apply(transaction).andThen(success -> finalResult);
						}
						else if(isTransactional && !result.isSuccess()) {
							// transactional and a failure, so roll back
							return rollbackFunction.apply(transaction).andThen(success -> finalResult);
						}
						else {
							// not transactional, so just return the result
							return result;
						}
					}
					catch(Throwable e) {
						return handleTransactionException(
								result,
								rollbackFunction,
								transaction,
								e.getMessage() == null ? e.getClass().getName() : e.getMessage()
						);
					}
				})
				.orElseGet(() -> Result.<V>builder()
						.withStatus(status())
						.addMessages(messages())
						.build()
				);
	}

	/**
	 * Handles the transaction failure by attempting to rollback
	 * @param result The result of the transaction-bounded operation
	 * @param rollbackFunction the rollback function
	 * @param transaction The transaction
	 * @param exceptionMessage The exception that occurred when attempting to call the transaction-bounded function,
	 * commit, or rollback the transaction
	 * @param <V> The result type
	 * @return The result of handling the failed transaction or a failure
	 */
	private <V> Result<V> handleTransactionException(final Result<V> result,
													 final Function<T, Result<Boolean>> rollbackFunction,
													 final T transaction,
													 final String exceptionMessage) {
		// at this point the function has to be transactional (i.e. the owner of the transaction) because
		// otherwise the result would have been returned without attempting to commit or roll back.
		// attempt to roll back
		String message = "";
		try {
			if(result == null) {
				message = "Exception thrown in supplied transaction-bounded function";
			}
			else {
				message = "Exception thrown when attempting to " +
						(result.isSuccess() ? "commit" : "rollback") +
						" the transaction";
			}
			final String message2 = message;
			return rollbackFunction.apply(transaction)
					.andThen(failure -> Result.<V>builder()
							.failed(message2)
							.addMessage("exception", exceptionMessage)
							.build()
					);
		}
		catch(Throwable e2) {
			return Result.<V>builder()
					.failed(message + ", and then again on the final rollback")
					.addMessage("exception", e2.getMessage())
					.addMessage("original_exception", exceptionMessage)
					.build();
		}

	}


	/**
	 * Calls the specified supplier if the result does not have a value
	 * @param supplier The supplier that returns the failed event
	 * @return the result from the supplier if this result is not a success
	 */
	public Result<T> onFailure(final Supplier<Result<T>> supplier) {
		return status() == Status.SUCCESS ? this : callResultSupplier(supplier);
	}

	/**
	 * Calls the specified function if the result does not have a value. The function is handed the result
	 * @param failureFunction The function to call if the result is not a success
	 * @return the result from the failure function
	 */
	public Result<T> onFailure(final Function<Result<T>, Result<T>> failureFunction) {
		if(status() == Status.SUCCESS) {
			return this;
		}
		else {
			try {
				return failureFunction.apply(this);
			}
			catch(Throwable e) {
				return Result.<T>builder()
						.failed("Exception thrown in specified failure function in Result.onFailure(...)")
						.addMessage("exception", e.getMessage())
						.addMessages(messages)
						.build();
			}
		}
	}

	/**
	 * When the this result is a success and the specified predicate
	 * @param predicate The predicate the determines whether to call the function
	 * @param predicateMet The function to call if the result is a success and the predicate evaluates to true
	 * @param predicateNotMet The supplier of the failure result when the predicate was not met
	 * @param <R> The type of the value held in the result
	 * @return A result from the function if the result is a success and the predicate evaluates to true, otherwise the
	 * result
	 */
	public <R> Result<R> meetsCondition(final Predicate<T> predicate,
										final Function<T, Result<R>> predicateMet,
										final Supplier<Result<R>> predicateNotMet) {
		Result<R> result;
		if(status() == Status.SUCCESS) {
			if(predicate.test(value)) {
				result = callResultFunction(value, predicateMet);
			}
			else {
				result = callResultSupplier(predicateNotMet);
			}
		}
		else {
			result = Result.<R>builder().withStatus(status()).addMessages(messages()).build();
		}
		return result;
	}

	/**
	 * When the this result is a success and the specified predicate
	 * @param predicate The predicate the determines whether to call the function
	 * @param predicateMet The function to call if the result is a success and the predicate evaluates to true
	 * @param predicateNotMet The supplier of the failure result when the predicate was not met
	 * @param <R> The type of the value held in the result
	 * @return A result from the function if the result is a success and the predicate evaluates to true, orherwise the
	 * result
	 */
	public <R> Result<R> meetsCondition(final Predicate<T> predicate,
										final Supplier<Result<R>> predicateMet,
										final Supplier<Result<R>> predicateNotMet) {
		Result<R> result;
		if(status() == Status.SUCCESS) {
			if(predicate.test(value)) {
				result = callResultSupplier(predicateMet);
			}
			else {
				result = callResultSupplier(predicateNotMet);
			}
		}
		else {
			result = Result.<R>builder().withStatus(status()).addMessages(messages()).build();
		}
		return result;
	}

	/**
	 * When the this result is a success and the specified predicate
	 * @param predicate The predicate the determines whether to call the function
	 * @param predicateMet The function to call if the result is a success and the predicate evaluates to true
	 * @param predicateNotMet The supplier of the failure result when the predicate was not met
	 * @param <R> The type of the value held in the result
	 * @return A result from the function if the result is a success and the predicate evaluates to true, orherwise the
	 * result
	 */
	public <R> Result<R> meetsCondition(final Predicate<T> predicate,
										final Function<T, Result<R>> predicateMet,
										final Function<T, Result<R>> predicateNotMet) {
		Result<R> result;
		if(status() == Status.SUCCESS) {
			if(predicate.test(value)) {
				result = callResultFunction(value, predicateMet);
			}
			else {
				result = callResultFunction(value, predicateNotMet);
			}
		}
		else {
			result = Result.<R>builder().withStatus(status()).addMessages(messages()).build();
		}
		return result;
	}

	/**
	 * When the this result is a success and the specified predicate
	 * @param predicate The predicate the determines whether to call the function
	 * @param predicateMet The function to call if the result is a success and the predicate evaluates to true
	 * @param predicateNotMet The supplier of the failure result when the predicate was not met
	 * @param <R> The type of the value held in the result
	 * @return A result from the function if the result is a success and the predicate evaluates to true, orherwise the
	 * result
	 */
	public <R> Result<R> meetsCondition(final Predicate<T> predicate,
										final Supplier<Result<R>> predicateNotMet,
										final Function<T, Result<R>> predicateMet) {
		Result<R> result;
		if(status() == Status.SUCCESS) {
			if(predicate.test(value)) {
				result = callResultSupplier(predicateNotMet);
			}
			else {
				result = callResultFunction(value, predicateMet);
			}
		}
		else {
			result = Result.<R>builder().withStatus(status()).addMessages(messages()).build();
		}
		return result;
	}

	/**
	 * Calls the consumer method if the result has a value present
	 * @param valueConsumer The value consumer function
	 */
	public void ifPresent(final Consumer<T> valueConsumer) {
		value().ifPresent(valueConsumer);
	}

	/**
	 * @param predicate The predicate that the value of this result must satisfy
	 * @return {@code true} if the value is present and meets the predicate; {@code false} otherwise
	 */
	public boolean satisfies(final Predicate<T> predicate) {
		return value().filter(predicate).isPresent();
	}

	/**
	 * Calls the consumer method if the result was successful and the value is present
	 * @param valueConsumer The value consumer function
	 */
	public void ifSuccess(final Consumer<T> valueConsumer) {
		if(status() == Status.SUCCESS) {
			ifPresent(valueConsumer);
		}
	}

	/**
	 * Calls the consumer method if the result was successful and then returns the original result
	 * @param valueConsumer The consumer of the result
	 * @return this result
	 */
	public Result<T> onSuccess(final Consumer<T> valueConsumer) {
		ifSuccess(valueConsumer);
		return this;
	}

	/**
	 * Calls the specified success consumer function if this result is a success, otherwise calls the specified failure
	 * consumer function.
	 * @param successFunction The success consumer function (accepts the value of this result)
	 * @param failureFunction The failure consumer function (accepts this result)
	 */
	public void ifResult(final Consumer<T> successFunction, final Consumer<Result<T>> failureFunction) {
		if(isSuccess()) {
			successFunction.accept(value);
		}
		else {
			failureFunction.accept(this);
		}
	}

	/**
	 * @return {@code true} if the status is success and the value is present
	 */
	public boolean isSuccess() {
		return status() == Status.SUCCESS && isPresent();
	}

	/**
	 * @return {@code true} if the value is present; {@code false} otherwise
	 */
	public boolean isPresent() {
		return value().isPresent();
	}

	/**
	 * Returns the specified value if the result does not have a value
	 * @param value The value to return
	 * @return The specified value or the value of the result
	 */
	public T orElse(final T value) {
		return value().orElse(value);
	}

	/**
	 * Returns the value returned by the supplier function if the result has no value
	 * @param valueSupplier The supplier function
	 * @return The value of the result or the supplied value
	 */
	public T orElseGet(final Supplier<T> valueSupplier) {
		return value().orElseGet(valueSupplier);
	}

	/**
	 * Returns the value returned by the specified function, which accepts this result
	 * @param valueFunction The function to call when the value isn't present
	 * @return The value of the result or the specified function
	 */
	public T orElseGet(final Function<Result<T>, T> valueFunction) {
		return value().orElseGet(() -> valueFunction.apply(this));
	}

	/**
	 * Throws the exception from the supplier if the result doesn't have a value
	 * @param exceptionSupplier The supplier of the exception
	 * @param <X> The exception's class type
	 * @return The value if the result has ont
	 *
	 * @throws X The exception returned by the supplier if the result has no value
	 */
	public <X extends Throwable> T orElseThrow(final Supplier<? extends X> exceptionSupplier) throws X {
		return value().orElseThrow(exceptionSupplier);
	}

	/**
	 * Throws the exception from the supplier if the result doesn't have a value
	 * @param exceptionSupplier The supplier of the exception
	 * @param <X> The exception's class type
	 * @return The value if the result has ont
	 *
	 * @throws X The exception returned by the supplier if the result has no value
	 */
	public <X extends Throwable> T orElseThrow(final Function<Result<T>, ? extends X> exceptionSupplier) throws X {
		return value().orElseThrow(() -> exceptionSupplier.apply(this));
	}

	/**
	 * Wraps the function call in a try/catch to ensure that the function call doesn't leak an exception. In the event
	 * that the function call throws an exception, then returns a failure result with the exception message
	 * @param value The value of the parameter to pass to the function
	 * @param function The function to call on the specified value
	 * @param <T> The type parameter of the specified value
	 * @param <R> The type parameter of the value returned in the result
	 * @return A result of the applied function call, or a failure if the specified function call throws
	 * an exception (which it isn't supposed to)
	 */
	private static <T, R> Result<R> callResultFunction(final T value, final Function<T, Result<R>> function) {
		try {
			return function.apply(value);
		}
		catch(Throwable e) {
			return Result.<R>builder()
					.failed("Exception thrown in specified function")
					.addMessage("exception", e.getMessage())
					.addMessage("value", value == null ? "[null]" : value.toString())
					.build();
		}
	}

	/**
	 * Wraps the call to the supplier in a try/catch to ensure that the call doesn't leak an exception. In the event that
	 * the call to the supplier throws an exception, then returns a failure result.
	 * @param supplier The supplier to call
	 * @param <R> The type parameter of the value returned in the result
	 * @return A result from the supplier, or a failure if the call to the specified supplier throws an exception
	 */
	private static <R> Result<R> callResultSupplier(final Supplier<Result<R>> supplier) {
		try {
			return supplier.get();
		}
		catch(Throwable e) {
			return Result.<R>builder()
					.failed("Exception thrown in specified supplier")
					.addMessage("exception", e.getMessage())
					.build();
		}
	}

	/**
	 * A builder for constructing validated results
	 * @param <V> The type parameter for the value
	 */
	public static class Builder<V> {
		@Nullable
		private V value;
		private Status status;
		private final Map<String, Object> messages = new LinkedHashMap<>();

		/**
		 * @param value The optional value of the result
		 * @return a reference to this builder for chaining
		 */
		public Builder<V> withValue(@Nullable final V value) {
			this.value = value;
			return this;
		}

		/**
		 * @param status The status of the operation
		 * @return a reference to this builder for chaining
		 */
		public Builder<V> withStatus(final Status status) {
			this.status = status;
			return this;
		}

		/**
		 * Adds a name-value pair as part of the message
		 * @param name The name
		 * @param message The value
		 * @return a reference to this builder for chaining
		 */
		public Builder<V> addMessage(final String name, final Object message) {
			pushBackMessage(name, message);
			return this;
		}

		/**
		 * Adds a set of name-value pairs to the message
		 * @param messages The messages
		 * @return a reference to this builder for chaining
		 */
		public Builder<V> addMessages(final Map<String, ?> messages) {
			messages.forEach(this::pushBackMessage);
			return this;
		}

		/**
		 * A successful result
		 * @param value The optional value of the result
		 * @return a reference to this builder for chaining
		 */
		public Builder<V> success(final V value) {
			this.status = Status.SUCCESS;
			this.value = value;
			return this;
		}

		/**
		 * A result that the object was not found
		 * @param message The error message associated with that request
		 * @return a reference to this builder for chaining
		 */
		public Builder<V> notFound(final String message) {
			this.status = Status.NOT_FOUND;
			pushBackMessage("error", message);
			return this;
		}

		/**
		 * A result that is a bad request
		 * @param message The error message associated with that request
		 * @return a reference to this builder for chaining
		 */
		public Builder<V> badRequest(final String message) {
			this.status = Status.BAD_REQUEST;
			pushBackMessage("error", message);
			return this;
		}

		/**
		 * A failed result
		 * @param message The error message associated with that request
		 * @return a reference to this builder for chaining
		 */
		public Builder<V> failed(final String message) {
			this.status = Status.FAILED;
			pushBackMessage("error", message);
			return this;
		}

		/**
		 * A result in which the connect failed
		 * @param message The error message associated with that request
		 * @return a reference to this builder for chaining
		 */
		public Builder<V> connectionFailed(final String message) {
			this.status = Status.CONNECTION_FAILED;
			pushBackMessage("error", message);
			return this;
		}

		/**
		 * When the result is indeterminant (i.e. not a definite answer)
		 * @param message The error message associated with that request
		 * @return a reference to this builder for chaining
		 */
		public Builder<V> indeterminant(final String message) {
			this.status = Status.INDETERMINANT;
			pushBackMessage("error", message);
			return this;
		}

		/**
		 * Accumulates the messages by pushing new messages with the same name on top of the stack. For example, if
		 * a message is added, {@code error -> error_1} and then another message is added, {@code error -> error_2}, the
		 * first message will be moved to {@code error_ -> error_1} (note the underscore). If then another message is
		 * added, {@code error -> error_3}, then we have {@code error -> error_3}, {@code error_ -> error_2}, {@code error__ -> error_1}.
		 * And so on. Note that this applies only if two messages have the same name.
		 * @param name The name of the message
		 * @param message The message object
		 */
		private void pushBackMessage(final String name, final Object message) {
			if(messages.containsKey(name)) {
				final Object object = messages.remove(name);
				messages.put(name, message);
				pushBackMessage(name + "_", object);
			}
			else {
				messages.put(name, message);
			}
		}

		/**
		 * @return A validated result instance
		 */
		public Result<V> build() {
			Objects.requireNonNull(status, () -> {
				final String message = "The status must be specified and cannot be null";
				LOGGER.error(message);
				throw new IllegalStateException(message);
			});

			// remove any empty keys and any null values from the map
			final Map<String, Object> cleanedMessages = messages.entrySet().stream()
					.filter(entry -> !Strings.isNullOrEmpty(entry.getKey()) && entry.getValue() != null)
					.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

			// set the status
			cleanedMessages.put(STATUS, status);

			return new Result<>(value, cleanedMessages);
		}
	}

	/**
	 * The status of the operation
	 */
	public enum Status {
		SUCCESS,
		NOT_FOUND,
		BAD_REQUEST,
		CONNECTION_FAILED,
		FAILED,
		INDETERMINANT
	}
}
