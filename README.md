## Recent Purposes

This small utility was created when my ex-startup closed due to the 2023 economical downturn. 

It was my first play with Kotlin. I will re-use it in order to test other kotlin persistence libraries. 

Goals: 
1. Pull out the sql queries from the code using [SQLDelight](https://cashapp.github.io/sqldelight)
2. if any? (control concurrency, today open bar)
3. ContextReceiver?
4. Better/Generic error handling ([arrow](https://arrow-kt.io/)? ) 

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
The utility will display in the console 
1. the number of invoice-files it has to copy 
2. a percentage progress after copying each file
3. and finally a success or error message `Succeeded to copy`

## Limitations
1. Using sequential flow or parallel copy processing take the same amount of time --> something wrong in our implementation or a limitation/constraint in the kotlin aws sdk s3-client?