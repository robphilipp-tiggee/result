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
all the exceptions, then we can have this. But now we have pushed all that logic up to the caller.

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

What the `Result` class does is push the logic for handling exceptions down to the method that are the 
source of the exceptional conditions. For example, when accessing a database, the repository method
catches all the database exceptions, and creates `Result` objects that wrap the results. The logic
for handling the exceptions is no where it should be, and the caller only needs to worry about success;
failures can be passed on, letting upstream code now that what it called failed.

In the `Result` example above, the call to `accountFrom(username)` returns a `Result<Account>`. When
an account was successfully found for the username, then that account is wrapped in the result. On
the other hand, if the account was not found, or if there was a database issue, or if more than one
account was returned, then the result wraps the failure.

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
        catch(NoResultException e)
        {
            return Result.<Product>builder()
                    .notFound("Unable to find the product with the requested ID")
                    .addMessage("product_id", productId)
                    .addMessage("exception", e.getMessage())
                    .build();
        }
        catch(NonUniqueResultException e)
        {
            return Result.<Product>builder()
                    .indeterminant("More than one product with the requested ID exists (should never happen)")
                    .addMessage("product_id", productId)
                    .addMessage("exception", e.getMessage())
                    .build();
        }
        catch(PersistenceException e)
        {
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
