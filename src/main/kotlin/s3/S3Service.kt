package s3

import Invoice
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.flow.*
import kotlin.math.round
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime


interface S3Service {
    fun ensureBucketExists(bucketName: String)
    suspend fun copyInvoiceFileToClientBucket(fromBucket: String, toBucket: String, invoices: List<Invoice>)
}


internal fun percentageRateWith2digits(number: Int, total: Int) = round(number.toFloat() * 10000 / total) / 100

fun s3Service(s3ClientWrapper: S3ClientWrapper): S3Service = S3ServiceImpl(s3ClientWrapper)

// TODO make wrapper private once perf implementation studied
class S3ServiceImpl(val s3ClientWrapper: S3ClientWrapper) : S3Service {


    override fun ensureBucketExists(bucketName: String) {
        runBlocking {
            require(s3ClientWrapper.execute(true, s3ClientWrapper.bucketExistCommand(bucketName))) { "Bucket $bucketName does not exist" }
        }
    }

    private suspend fun createBucket(bucketName: String) {
        s3ClientWrapper.execute(Unit, s3ClientWrapper.createBucketCommand(bucketName))
    }


    @OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
    override suspend fun copyInvoiceFileToClientBucket(fromBucket: String, toBucket: String, invoices: List<Invoice>) {
        createBucket(toBucket)

        val size = invoices.size
        val concurrency = 1000
        val dispatcher = Dispatchers.Default.limitedParallelism(concurrency)
        val duration = measureTime {
            copyInvoiceBase(invoices, fromBucket, toBucket, dispatcher, size)
        }
        println("Copy duration: $duration")
    }
}

suspend fun S3ServiceImpl.copyInvoiceBase(
    invoices: List<Invoice>,
    fromBucket: String,
    toBucketName: String,
    dispatcher: CoroutineDispatcher,
    size: Int
) {
    coroutineScope {
        invoices.map { invoice -> s3ClientWrapper.copyInvoiceDocumentCommand(fromBucket, toBucketName, invoice) }.map {
            this.launch(dispatcher) {
                s3ClientWrapper.execute(Unit, it)
            }
        }.forEachIndexed { index, it ->
            it.join()
            if (index % 100 == 0)
                println("${percentageRateWith2digits(index, size)}%")
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun S3ServiceImpl.copyInvoiceBaseWithChannel(
    invoices: List<Invoice>,
    fromBucket: String,
    toBucketName: String,
    dispatcher: CoroutineDispatcher,
    size: Int
) {
    supervisorScope {
        val channel = produce(capacity = 300) {
            invoices.map { invoice -> s3ClientWrapper.copyInvoiceDocumentCommand(fromBucket, toBucketName, invoice) }.map {
                this.launch(dispatcher) {
                    s3ClientWrapper.execute(Unit, it)
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

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun S3ServiceImpl.copyInvoiceBaseWithChannel2(
    invoices: List<Invoice>,
    fromBucket: String,
    toBucketName: String,
    dispatcher: CoroutineDispatcher,
    size: Int,
    concurrency: Int,
) {
    supervisorScope {
        val commandsChannel = produce(capacity = size) {
            invoices.map { invoice -> s3ClientWrapper.copyInvoiceDocumentCommand(fromBucket, toBucketName, invoice) }
                .forEach {
                    send(it)
                }
        }

        val counterChannel = produce(capacity = size) {
            repeat(concurrency) {
                for (command in commandsChannel) {
                    launch(dispatcher) {
                        s3ClientWrapper.execute(Unit, command)
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

private suspend fun S3ServiceImpl.copyInvoices2(
    invoices: List<Invoice>,
    fromBucket: String,
    toBucketName: String,
    dispatcher: CoroutineDispatcher,
    size: Int,
    concurrency: Int,
) =
    invoices.asFlow()
        .map { invoice -> s3ClientWrapper.copyInvoiceDocumentCommand(fromBucket, toBucketName, invoice) }
        .concurrentMap(dispatcher, concurrency) {
            s3ClientWrapper.execute(Unit, it)
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


@OptIn(FlowPreview::class)
private suspend fun S3ServiceImpl.copyInvoices3(
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
        .map { invoice -> s3ClientWrapper.copyInvoiceDocumentCommand(fromBucket, toBucketName, invoice) }
        .chunked(chunkSize).asFlow()
        .onStart { println("Start copy") }
        .flatMapMerge(concurrency) { copyCommands ->
            copyCommands.asFlow().onEach { s3ClientWrapper.execute(Unit, it) }
        }
        .flowOn(dispatcher)
        .scan(0) { acc, _ -> acc + 1 }
        .onCompletion { println("Copy done ") }
        .filter { it % 100 == 0 }
        .collect {
            println("${percentageRateWith2digits(it, size)}%")
        }
}