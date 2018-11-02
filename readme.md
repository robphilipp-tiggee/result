# result<t>

The `Result` class provides a functional approach for managing results from calls that may fail.

## intro and motivation

For example, given
```java
public class MyUserRepo {
    public Result<Account> accountFrom(final String username) { ... }
}

public class MyInvoiceRepo {
    public Result<Invoice> invoiceFor(final long accountId, final LocalDate billingPeriodStart) { ... }
}

public class MyPaymentProcessor {
    public Result<Payment> processPayment(final long invoiceId, final BigDecimal amount) { ... }
}
```
and our task is to process the payment for the user's account, for the balance in the invoice. How this
would look when using the `Result` class is shown below. 

```java
public class Main {
    public Result<Payment> processPaymentFor(final String username) {
        return accountRepo.accountFrom(username)
            .andThen(account -> invoiceRepo.invoiceFor(account.accountId(), now()))
            .andThen(invoice -> processor.processPayment(invoice.invoiceId(), invoice.balance()));
    }
}
```

Without the `Result` class, you would need to check the result, catch exceptions, and wrap results
into `if` statements. When assuming that the caller to `Main.processPaymentFor(...)` will handle
all the exceptions, then we can have this. But now we have pushed all that error handling logic up 
to the caller.

```java
public class Main {
    public Payment processPaymentFor(final String username) {
        Account account = accountRepo.accountFrom(username);
        if(Objects.nonNull(account)) {
            Invoice invoiceRepo.invoiceFor(account.accountId(), now());
            if(Objects.nonNull(invoice)) {
                return processor.processPayment(invoice.invoiceId, invoice.balance());
            }
            else {
                throw new InvoiceNotFoundException();
                // or 
                // return Payment.empty()
            }
        }
        else {
            throw new AccountNotFoundException();
            // or
            // return Payment.empty()
        }
    }
}
```
When the account is not found or is `null`, then we need to either throw an exception, or return an empty
`Payment` object. Neither of these are ideal, and maintaining consistency throughout the code becomes
cumbersome.

What the `Result` class does is push the logic for handling exceptions down to the methods that are the 
source of the exceptional conditions. For example, when accessing a database, the repository method
catches all the database exceptions, and creates a `Result` object that wraps the result. The logic
for handling the exceptions is now where it should be, and the caller only needs to worry about success;
failures can be passed on, letting upstream code now that the call resulted in a failure.

In the `Result` example above, the call to `accountFrom(username)` returns a `Result<Account>`. When
an account is successfully found for the username, then that account is wrapped in the result. On
the other hand, if the account is not found, or if there is a database issue, or if more than one
account is returned, then the result wraps the failure.

The `result.andThen(...)` method only executes the function specified in the argument if the result
on which it is being called is a success. Otherwise, it's type is mapped to match the same type that
the function returns. In this way, in the above example, even if the account cannot be found, a 
`Result<Payment>` is returned, and that result is a failure.

The sample code below shows an example of a repository that returns a product based on a product ID.
```java
public class MyOtherRepo {
    public Result<Product> productFor(final long productId) {
        try {
            final ProductsDao dao = entityManager
                    .createQuery(
                            "select product from ProductsDao as product where product.id = :" + PRODUCT_ID,
                            ProductsDao.class
                    )
                    .setParameter(PRODUCT_ID, productId)
                    .getSingleResult();
            
            return Result.<Product>builder().success(convertToProduct(dao)).build();
        }
        catch(NoResultException e) {
            return Result.<Product>builder()
                    .notFound("Unable to find the product with the requested ID")
                    .addMessage("product_id", productId)
                    .addMessage("exception", e.getMessage())
                    .build();
        }
        catch(NonUniqueResultException e) {
            return Result.<Product>builder()
                    .indeterminant("More than one product with the requested ID exists (should never happen)")
                    .addMessage("product_id", productId)
                    .addMessage("exception", e.getMessage())
                    .build();
        }
        catch(PersistenceException e) {
            return Result.<Product>builder()
                    .failed("Unable to retrieve requested product")
                    .addMessage("product_id", productId)
                    .addMessage("exception", e.getMessage())
                    .build();
        }
    }
}
```

## usage

In the next sections we describe how to use `Result`. Broadly speaking, to use a result, you
will need to know how to 
1.  construct the result, 
2.  query the result for status, values, and messages
3.  transform results
4.  chain results
5.  create and manage transactional boundaries

### anatomy

A `Result` has three parts.
1.  `status` - the status can be success, failed, bad request, indeterminant, not found, connection failed.
2.  `value` - the value wrapped by the `Result`
3.  `messages` - messages describing the outcome of the result

And there are some rules
1.  Every success `Result` must have a value.
2.  Every non-success `Result` must have an `error` message.

And there are some basic conventions
1.  **Unsuccessful outcomes do not have a value**. 
    When an outcome fails, the action does not result in a value. Therefore, unsuccessful `Result`s
    do not have a value.
2.  **Messages are not intended to hold the outcome of a success**.
    Messages should merely be informational. The outcome of a successful action should be
    encapsulated in the `Result` value. A successful outcome, may be enhanced by some 
    informational messages.
3.  **Unsuccessful outcomes should be explained**.
    When an action fails, the `Result` should have messages explaining the failure and provide
    relevant state information to help understand the failure.

### construction

The `Result` is constructed with using a builder that helps manage the rules and conventions listed
in the previous section. To construct a `Result` describing an action's successful outcome, we can
use the builder's `success(...)` method. The following example shows how to create a `Result` that
wraps a `Product`, and represents the successful outcome of, say, retrieving a product from some
data store.

```java
final Product desiredProduct = ...;
final Result<Product> result = Result.<Product>builder().success(desiredProduct).build();
```
Notice that the `success(...)` method expects a `Product`. Generally, the generic type, `T`, of the
`Result<T>` is the argument required by the `success(final T value)` method.

Suppose the desired product was not found in our data store. In this case, we can represent
this in one of two ways. We can treat this as a failure, or as a not-found.

```java
final Product desiredProduct = ...;     // not found
final Result<Product> failed = Result.<Product>builder()
    .failed("Product not found")
    .addMessage("product_id", productId)
    .build();
// or
final Result<Product> notFound = Result.<Product>builder()
    .notFound("Product not found")
    .addMessage("product_id", productId)
    .build();
```
In both cases, the `Result` represents the fact that the outcome was not successful. The only
difference in the above two `Result`s is the status: in the first case it will be `Result.Status.FAILED`
and in the second case it will be `Result.Status.NOT_FOUND`.
 
### querying

When a method call returns a `Result`, we need to be able to query that result to determine whether
the outcome was a success, and if so, get the value. Or, if the outcome failed, what type of failure
and why did it fail.

Recall the `MyOtherProduct.productFor(final long productId)` method from earlier. This method returns
a `Result<Product>`, and specifically, captures four possible outcomes.
1.  `success` - When the product is found based on its product ID, then returns the `Product`.
2.  `not found` - When no product is found with the specified product ID, then returns the status
    `Result.Status.NOT_FOUND` and three messages: the required `error` message; the requested product ID;
    and, the message from the caught exception.
3.  `indeterminant` - When more than one product is found with the "unique" ID, then returns the
    status `Result.Status.INDETERMINANT` and three messages: the required `error` message; the
    request product ID; and, the message from the caught exception.
4.  `failed` - When there is a persistence exception other than the two preceding it, then returns the
    status `Result.Status.FAILED` and, again, three messages: the required `error` message; the
    request product ID; and, the message from the caught exception. In this case, the exception message
    may supply us with relevant information. For example, maybe we couldn't connect to the database,
    or was the SQL invalid, etc.
    
The most basic way to determine the status of a `Result` is the `status()` method. For example, suppose
we have a method that returns a `Result<Product>` based on a specified product ID.

```java
public Result<Product> productFor(final long productId) {}
    final Product desiredProduct = ...;
    return Result.<Product>builder().success(desiredProduct).build();
}
```
Then we can call that method and query the outcome status.
```java
// ...
final Result<Product> result = productFor(314);
if(result.status() == Result.Status.SUCCESS) {
    // do something
}
else {
    // do something else
}
```
Alternatively, you could call the `isSuccess()` method which requires that a value has been set.
```java
// ...
final Result<Product> result = productFor(314);
if(result.isSuccess()) {
    // do something
}
else {
    // do something else
}
```

In many cases, we want the value when successful, or some default value when it failed.

```java
final Product product = productFor(314).orElse(Product.empty());
// or
final Product product = productFor(314).orElseGet(() -> Product.empty());
// or
final Product product = productFor(314).orElseGet(result -> {
    LOGGER.warn("Product not found; messages: {}", result.messages());
    return Product.empty();
});
```

You may want to perform an action, only when the outcome succeeded, but keep the original `Result`.
```java
final Result<Product> result = productFor(314)
    .onSuccess(product -> LOGGER.info("Got my product; product ID: {}", product.productId()));
// or
productFor(314)
    .ifSuccess(product -> LOGGER.info("Got my product; product ID: {}", product.productId()));
```
In the first case the `result` returned from the `onSuccess(...)` method is a reference to the 
`Result` returned from `productFor(314)`, and we logged the fact that the product was successfully 
retrieved. In the second case, the `ifSuccess(...)` method does not return anything, and the message 
is only logged.

You may also want to do something based on the result being a success, and the value of the result
satisfying some condition.

```java
if(productFor(314).satisfies(value -> value > 100 * Math.PI)) { ... }
```
The `satisfies(...)` methods accepts a `Predicate` and returns the result of evaluating the result's
value against the predicate.

The `Result` class provides a number of methods for querying the results. Please see the java docs.

### chaining and transformations

The power of the `Result` class comes from its ability to map, flat-map, and chain results. 
The `Result` class is a monad that provides map and flat-ap (andThen) operations.

Recall the earlier code snippet.
```java
public class Main {
    public Result<Payment> processPaymentFor(final String username) {
        return accountRepo.accountFrom(username)
            .andThen(account -> invoiceRepo.invoiceFor(account.accountId(), now()))
            .andThen(invoice -> processor.processPayment(invoice.invoiceId(), invoice.balance()));
    }
}
```
Here we get a `Result<Account>` from the `accountFrom(...)` method. We then do a flat-map operation
on the `Result<Invoice>` returned from the `invoiceFor(...)` method, which results in a `Result<Invoice>`.
And then, we do another flat-map operation on the `Result<Payment>` returned from the `processPayment(...)`
method, which ultimately returns a `Result<Payment>`. If any of the steps fail, the final `Result<Payment>`
represents a failure, but no matter which result failed, a `Result<Payment>` is **always** returned.

Suppose the call to `accountFrom(...)` failed because the username didn't exist. In this case, neither
the `invoiceFor(...)` method nor the `processPayment(...)` would be called. Rather, they would be 
short-circuited, and a failure `Result<Payment>` would be returned.

The result values can also be mapped. For a contrived example, suppose in the code snippet above, you would like
to return `Result<Account>` rather than the `Result<Payment>`. The code snippet below shows how.

```java
public class Main {
    public Result<Account> processPaymentFor(final String username) {
        return accountRepo.accountFrom(username)
            .andThen(account -> invoiceRepo.invoiceFor(account.accountId(), now())
                .andThen(invoice -> processor.processPayment(invoice.invoiceId(), invoice.balance()))
                .map(payment -> account)
            );
    }
}
```
In this case, we need to keep `account` in scope for the `map(...)` function, and then just simply
map the payment value to the account value.

#### transformations based on success

Cases arise where the status of the result determines that transformation. The `Result` class provides
a variant of the `andThen(...)` method that accepts two functions, the first is called when the status is
a success, and the second is called when the status is **not** a success.

```java
public class Main {
    public Result<Payment> processPaymentFor(final String username) {
        return accountRepo.accountFrom(username)
            .andThen(account -> invoiceRepo.invoiceFor(account.accountId(), now()))
            .andThen(invoice -> processor.processPayment(invoice.invoiceId(), invoice.balance()))
            .andThen(
            		payment -> audit.log(payment).map(logged -> payment),       // payment succeeded
            		result -> audit.logFailure(result).map(logged -> payment)   // payment failed
            );
    }
}
```

In this case, if the payment was successfully processed, then it is logged. Otherwise, the failure is 
logged. The above code snippet makes the assumption that the calls to log the payment or failure, 
both return a `Result<T>`, and therefore we can map that result back to the required `Payment` object.

#### transformation for success based on value

Basing the transformation of a successful result on the value is also a common need. The `Result` class
provides a four `meetsCondition(...)` methods for this use case. The `meetsCondition(...)` methods
all have the same semantics, but differ on whether the arguments are suppliers or functions.

The `meetsCondition(...)` methods are defined by `meetsCondition = f(predict, meetsPredicate, doesNotMeetPredicate): result`.
The function takes a predicate that it applies against the value of the result, and then if the value
meets the predicate, calls the `meetsPredicate` function (or supplier). If the value doesn't meet
the predicate, then calls the `doesNotMeetPredicate` function (or supplier).

```java
public class Main {
    public Result<Payment> processPaymentFor(final String username) {
        return accountRepo.accountFrom(username)
            .andThen(account -> invoiceRepo.invoiceFor(account.accountId(), now()))
            .meetsCondition(
                    invoice -> invoice.balance() > 0,
                    invoice -> processor.processPayment(invoice.invoiceId(), invoice.balance()),
                    invoice -> Result.<Payment>builder().success(Payment.empty()).build()
            );
    }
}
```
In the above example, if the invoice has no balance, then there is no need to process the payment,
and instead, we can just return a success result wrapping an empty payment. The four variations are
shown below and provide combinations of suppliers and functions.

```java
<R> Result<R> meetsCondition(
		Predicate<T> predicate, 
		Supplier<Result<R>> predicateMet, 
		Supplier<Result<R>> predicateNotMet) {...}
		
<R> Result<R> meetsCondition(
		Predicate<T> predicate, 
		Function<T, Result<R>> predicateMet, 
		Function<T, Result<R>> predicateNotMet) {...}
		
<R> Result<R> meetsCondition(
		Predicate<T> predicate, 
		Supplier<Result<R>> predicateMet, 
		Function<T, Result<R>> predicateNotMet) {...}
		
<R> Result<R> meetsCondition(
		Predicate<T> predicate, 
		Function<T, Result<R>> predicateMet, 
		Supplier<Result<R>> predicateNotMet) {...}
```

### transactions

The `Result` class provides a generalized mechanism for managing transaction boundaries across
chained results. In this way, the transactions can span multiple data sources, and, for example,
roll-backs can be tailored to the specifics of your code.