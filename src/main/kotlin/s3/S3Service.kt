package s3

import BucketError
import CopiesFailures
import Invoice
import arrow.core.Either
import arrow.core.raise.either
import arrow.fx.coroutines.parMapOrAccumulate
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.flow.*
import kotlin.math.round
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

internal fun percentageRateWith2digits(number: Int, total: Int) = round(number.toFloat() * 10000 / total) / 100

interface S3Service {
    suspend fun ensureBucketExists(bucketName: String): Either<BucketError, Unit>
    suspend fun copyInvoiceFileToClientBucket(fromBucket: String, toBucket: String, invoices: List<Invoice>): Either<BucketError, Unit>
}

fun s3Service(s3ClientWrapper: S3ClientWrapper) = object : S3Service {

    //    context(Raise<BucketError>) ERROR when declared
    override suspend fun ensureBucketExists(bucketName: String): Either<BucketError, Unit> =
        s3ClientWrapper.execute(s3ClientWrapper.bucketExistCommand(bucketName))


    private suspend fun createBucket(bucketName: String) =
        s3ClientWrapper.execute(s3ClientWrapper.createBucketCommand(bucketName))


    @OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
    override suspend fun copyInvoiceFileToClientBucket(fromBucket: String, toBucket: String, invoices: List<Invoice>): Either<BucketError, Unit> =
        either {

            createBucket(toBucket).bind()

            val size = invoices.size
            val concurrency = 1000
            val dispatcher = Dispatchers.Default.limitedParallelism(concurrency)
            val duration = measureTime {
                with(s3ClientWrapper) {
                    // TODO When failing the flow is broken before login the copy duration
                    // copyInvoiceWithArrowConcurrency(invoices, fromBucket, toBucket, dispatcher, size).bind()
                    copyInvoiceBase(invoices, fromBucket, toBucket, dispatcher, size)
                }
            }
            println("Copy duration: $duration")
        }
}


context(S3ClientWrapper)
private suspend fun copyInvoiceBase(
    invoices: List<Invoice>,
    fromBucket: String,
    toBucketName: String,
    dispatcher: CoroutineDispatcher,
    size: Int
) /*: Either<BucketError, Unit> */ =
    coroutineScope {
            invoices.map { invoice -> copyInvoiceDocumentCommand(fromBucket, toBucketName, invoice) }.map {
                this@coroutineScope.launch(dispatcher) {
                    either { execute(it).bind() }
                        // Trick to send the error another consumer (console) without splitting this flow...
                        .mapLeft { println(it) }
                }
            }.forEachIndexed { index, it ->
                // whatever the success or failure we can still monitor progress
                it.join()
                if (index % 100 == 0)
                    println("${percentageRateWith2digits(index, size)}%")
            }
        }


// TODO not able to monitor progress
context(S3ClientWrapper)
private suspend fun copyInvoiceWithArrowConcurrency(
    invoices: List<Invoice>,
    fromBucket: String,
    toBucketName: String,
    dispatcher: CoroutineDispatcher,
): Either<BucketError, List<Unit>> =
    invoices
        .map { invoice -> copyInvoiceDocumentCommand(fromBucket, toBucketName, invoice) }
        .parMapOrAccumulate(context = dispatcher, concurrency = 1000) {
            execute(it).bind()
        }.mapLeft {
            CopiesFailures(toBucketName, it.toList())
        }
//        .forEachIndexed { index, _ ->
//            //it.
//            if (index % 100 == 0)
//                println("${percentageRateWith2digits(index, size)}%")
//        }



// --- Other attempts not migrated to arrow Type error handling
context(S3ClientWrapper)
@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun copyInvoiceBaseWithChannel(
    invoices: List<Invoice>,
    fromBucket: String,
    toBucketName: String,
    dispatcher: CoroutineDispatcher,
    size: Int
) {
    supervisorScope {
        val channel = produce(capacity = 300) {
            invoices.map { invoice -> copyInvoiceDocumentCommand(fromBucket, toBucketName, invoice) }.map {
                this.launch(dispatcher) {
                    execute(it)
                    send("plop")
                }
            }
        }

        var count = 0
        for (s in channel) {
            count++
            if (count % 100 == 0)
                println("${percentageRateWith2digits(count, size)}%")
        }

    }
}

context(S3ClientWrapper)
@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun copyInvoiceBaseWithChannel2(
    invoices: List<Invoice>,
    fromBucket: String,
    toBucketName: String,
    dispatcher: CoroutineDispatcher,
    size: Int,
    concurrency: Int,
) {
    supervisorScope {
        val commandsChannel = produce(capacity = size) {
            invoices.map { invoice -> copyInvoiceDocumentCommand(fromBucket, toBucketName, invoice) }
                .forEach {
                    send(it)
                }
        }

        val counterChannel = produce(capacity = size) {
            repeat(concurrency) {
                for (command in commandsChannel) {
                    launch(dispatcher) {
                        execute(command)
                        send("plop")
                    }
                }

            }
        }

        var count = 0
        for (s in counterChannel) {
            count++
            if (count % 100 == 0)
                println("${percentageRateWith2digits(count, size)}%")
        }

    }
}

context(S3ClientWrapper)
private suspend fun copyInvoices2(
    invoices: List<Invoice>,
    fromBucket: String,
    toBucketName: String,
    dispatcher: CoroutineDispatcher,
    size: Int,
    concurrency: Int,
) =
    invoices.asFlow()
        .map { invoice -> copyInvoiceDocumentCommand(fromBucket, toBucketName, invoice) }
        .concurrentMap(dispatcher, concurrency) {
            execute(it)
        }
        .scan(0) { acc, _ -> acc + 1 }
        .collect {
            if (it % 100 == 0)
                println("${percentageRateWith2digits(it, size)}%")
        }

@OptIn(FlowPreview::class)
private fun <T, R> Flow<T>.concurrentMap(dispatcher: CoroutineDispatcher, concurrencyLevel: Int, transform: suspend (T) -> R): Flow<R> {
    return flatMapMerge(concurrencyLevel) { value ->
        flow { emit(transform(value)) }
    }.flowOn(dispatcher)
}

context(S3ClientWrapper)
@OptIn(FlowPreview::class)
private suspend fun copyInvoices3(
    invoices: List<Invoice>,
    fromBucket: String,
    toBucketName: String,
    dispatcher: CoroutineDispatcher,
    size: Int,
    concurrency: Int,
) {
    val chunkSize = Integer.max(round(size / concurrency.toFloat()).toInt(), 1)
    println("chunksize: $chunkSize")
    invoices
        .map { invoice -> copyInvoiceDocumentCommand(fromBucket, toBucketName, invoice) }
        .chunked(chunkSize).asFlow()
        .onStart { println("Start copy") }
        .flatMapMerge(concurrency) { copyCommands ->
            copyCommands.asFlow().onEach { execute(it) }
        }
        .flowOn(dispatcher)
        .scan(0) { acc, _ -> acc + 1 }
        .onCompletion { println("Copy done ") }
        .filter { it % 100 == 0 }
        .collect {
            println("${percentageRateWith2digits(it, size)}%")
        }
}