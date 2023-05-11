## Recent Purposes

This small utility was created when my ex-startup closed due to the 2023 economical downturn. 

It was my first play with Kotlin. I will re-use it in order to test other kotlin persistence libraries. 

Goals: 
1. Pull out the sql queries from the code using [SQLDelight](https://cashapp.github.io/sqldelight)
2. control concurrency (different implementation, below small performance comparison)
3. ContextReceiver
4. Better/Generic error handling (Use[arrow](https://arrow-kt.io/) Effect to pull out exception handling outside the model. [Practical explanation](https://www.youtube.com/watch?v=T04ynq2IVFs) , [Types Error Handling Comparison in Kotlin](https://betterprogramming.pub/typed-error-handling-in-kotlin-11ff25882880)) 
5. Context Receiver to hide Arrow either type: Not possible yet, ContextReceiver is still experimental and the [Kotlin compiler is still shaky about it](https://slack-chats.kotlinlang.org/t/9524257/first-compile-with-the-latest-alpha-caused-by-java-lang-ille#14c8ba77-e851-48d1-8399-2ef96858f568)  /!\ POSTPONE

---
---

## Old Purposes

Utilities that copies the files of the client's complete invoices from the environment bucket to a `agapio-client-${formated-client-name}-${env}`

The bucket is split by restaurant and then by month (of the invoice).

The invoice naming `yyyy-MM-dd - ${formatted supplier-name} - {hash of the document id} - EUR - ${formatted invoice amount incl with "_"}.${deposit_extension}`

## Requirement

* Jdk >= 19
* Gradle

## Configuration

You will need to fill the [application.json](src/main/resources/application.json) with

* the postgres credential information (`sslMode` should be set to `"REQUIRE"` and `trustAll` to `true` on heroku environment)
* the aws credential information (`dryRun` can be used to prevent any bucket creation of file copy on s3)
* an `env` property used to suffix the source bucket name

For now the execution arguments are pass via the same [application.json](src/main/resources/application.json):

* `clientId`: the id of the client you want to extract the files of the complete invoices in a separate bucket
* `depositStartDateIncl` & `depositEndDateExcl` is the range of the initial deposit `created_date` to scan for the complete invoices to extract their documents.
  The format should be `yyyy-MM-dd`

##  Execution
The utility displays in the console 
1. the number of invoice-files it has to copy 
2. a percentage progress regularly

---
---

## Performance discussion

Every result is in second and represents only the duration of the all copy.

1. The Normal one is a naive for loop that run all copy over a dispatcher whose parallelism is limited. And then wait for all job to be done.
2. The Arrow one uses Arrow parMap, which internally uses a semaphore to control the concurrency of jobs (and still handle types errors without additional actions)
3. The Flow-concurrentMap uses Flow#flatMapMerge to have control over concurrency
4. The Flow-chunk-flatMapMerge chunks the collection into "concurrency smaller collections" before using  Flow#flatMapMerge also limited with its concurrency level

Arrow implementation has the best performance over the 2 campaigns. Since it handles internally the handling of types errors it has a better readability. 
But it requires importing _arrow-fx-coroutines_ library only for this function. 
So, I would go with implementation 3 or 4 depending on the number of files to copy.

I was expecting implementation 4 (with the chunk) to be better than implementation 3 since we create fewer flows ( #chunks flows vs #files flows). It's not the case.

**Disclaimer:** I suspect that s3 api has a high variance. To valid/invalid this assumption, it would have been even better to turn the same campaign at different time.  

Below the two campaigns, each implementation has been turn in a row 10 times.

### 586 files to copy with concurrency limit to 100

|concurrency 100|Normal|Arrow|Flow-concurrentMap|Flow-chunk-flatMapMerge|
|---------------|------|-----|------------------|-----------------------|
|round 1        |23.4  |15.9 |16.16             |16.53                  |
|round 2        |21.5  |19.61|16.45             |16.7                   |
|round 3        |16.69 |16.13|18.28             |15.89                  |
|round 4        |16.65 |15.17|16.92             |15.82                  |
|round 5        |16.1  |16.27|16.46             |18.34                  |
|round 6        |16.45 |16.44|16.81             |16.37                  |
|round 7        |17.83 |16.05|17.37             |16.43                  |
|round 8        |15.85 |16.09|17.11             |15.88                  |
|round 9        |16.01 |15.9 |16.53             |16.11                  |
|round 10       |15.76 |15.84|16.79             |16.07                  |
|average        |17.624|16.34|16.888            |16.414                 |


### 6722 files to copy with concurrency limit to 1000

|concurrency 1000|Normal |Arrow  |Flow-concurrentMap|Flow-chunk-flatMapMerge|
|----------------|-------|-------|------------------|-----------------------|
|round 1         |308.06 |239.96 |252.25            |229.16                 |
|round 2         |303.98 |230.64 |258.61            |234.67                 |
|round 3         |281.02 |238.02 |259.19            |243.87                 |
|round 4         |273.04 |231.34 |243.03            |241.69                 |
|round 5         |268.04 |222.67 |241.19            |244.63                 |
|round 6         |259.98 |231.99 |227.67            |249.69                 |
|round 7         |252.6  |217.78 |228.31            |245.42                 |
|round 8         |249.67 |218.47 |231.61            |237.49                 |
|round 9         |237.82 |228.19 |226.53            |237.92                 |
|round 10        |234.85 |235.79 |227.88            |227.94                 |
|average         |266.906|229.485|239.627           |239.248                |
