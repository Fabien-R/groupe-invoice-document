## Purposes

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
1. The postgres client we used is based on vertx; it's java library and so don't use Kotlin way of doing things. So I have mixed two ways of handling blocking code.
2. /!\ Because of this mix **_if the code is running more than 1 minute, you will see in the console some error-messages_** indicating that the `worker_thread has been blocked`. These errors do not prevent the utilities to do the work but mean it has not been correctly implemented in vertx.
3. etc.