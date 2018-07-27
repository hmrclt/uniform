---
layout: home
title:  "Home"
section: "home"
---

Uniform is an extension for the Play Framework that makes it possible to write multi-page journeys using for comprehensions - 

```scala
val journey = for {
   customerAddress   <- ask(addressBinding, "customerAddress")
   billingAddress    <- ask(addressBinding, "billingAddress")
   creditCardDetails <- ask(ccBinding, "creditCardDetails")
   pay               =  PaymentDetails(customerAddress, billingAddress, creditCardDetails)
   _                 <- confirm(pay) >>= takePayment(pay)
} yield ()
```
