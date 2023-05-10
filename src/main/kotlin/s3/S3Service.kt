package s3

import BucketError
import CopiesFailures
import Invoice
import arrow.core.Either
import arrow.core.flattenOrAccumulate
import arrow.core.raise.either
import arrow.fx.coroutines.parMapOrAccumulate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.round
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

const val PROGRESSION_DELAY_MILLIS = 10000L

internal fun percentageRateWith2digits(number: Int, total: Int) = round(number.toFloat() * 10000 / total) / 100

context(CoroutineScope)
        private fun launchConsoleDisplayer(
    dispatcher: CoroutineDispatcher,
    progress: MutableStateFlow<Progress>
): Job {
    val consoleDisplayer = launch(dispatcher) {
        do {
            progress.value.let { println("${percentageRateWith2digits(it.current, it.total)}%") }
            delay(PROGRESSION_DELAY_MILLIS)
        } while (true)
    }
    return consoleDisplayer
}

interface S3Service {
    suspend fun ensureBucketExists(bucketName: String): Either<BucketError, Unit>
    suspend fun copyInvoiceFileToClientBucket(fromBucket: String, toBucket: String, invoices: List<Invoice>): Either<BucketError, Unit>
}

fun s3Service(s3ClientWrapper: S3ClientWrapper) = object : S3Service {

    override suspend fun ensureBucketExists(bucketName: String): Either<BucketError, Unit> =
        s3ClientWrapper.execute(s3ClientWrapper.bucketExistCommand(bucketName))


    private suspend fun createBucket(bucketName: String) =
        s3ClientWrapper.execute(s3ClientWrapper.createBucketCommand(bucketName))


    @OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
    override suspend fun copyInvoiceFileToClientBucket(fromBucket: String, toBucket: String, invoices: List<Invoice>): Either<BucketError, Unit> =
        either {

            createBucket(toBucket).bind()
            val concurrency = 101
            val dispatcher = Dispatchers.Default.limitedParallelism(concurrency)

            // conflated
            val progress: MutableStateFlow<Progress> = MutableStateFlow(Progress(0, 0, invoices.size))

            coroutineScope {
                val consoleDisplayer = launchConsoleDisplayer(dispatcher, progress)
                var copy: Either<CopiesFailures, List<Unit>>
                val duration = measureTime {
                    with(s3ClientWrapper) {
//                        copy = copyInvoicesWithArrowConcurrency(invoices, fromBucket, toBucket, dispatcher, progress)
                        copy = copyInvoicesBase(invoices, fromBucket, toBucket, dispatcher, progress)
//                        copy = copyInvoices2(invoices, fromBucket, toBucket, dispatcher, concurrency, progress)
//                        copy = copyInvoices3(invoices, fromBucket, toBucket, dispatcher, concurrency, progress)
                    }
                }

                println("Copy duration: $duration")
                consoleDisplayer.cancel()
                // FIXME Not satisfying. Returning an either should be cleaner + not need to interrupt the flow here
                copy.bind() // binding later to have the copy duration
            }
        }
}


context(S3ClientWrapper, CoroutineScope)
        private suspend fun copyInvoicesBase(
    invoices: List<Invoice>,
    fromBucket: String,
    toBucketName: String,
    dispatcher: CoroutineDispatcher,
    progress: MutableStateFlow<Progress>
): Either<CopiesFailures, List<Unit>> =
    invoices.map { invoice -> copyInvoiceDocumentCommand(fromBucket, toBucketName, invoice) }.map {
        async(dispatcher) {
            either { executeWithProgress(it, progress).bind() }
        }
    }.awaitAll().flattenOrAccumulate().mapLeft {
        CopiesFailures(toBucketName).appendAll(it.toList())
    }


data class Progress(val current: Int, val failures: Int, val total: Int)

context(S3ClientWrapper)
private suspend fun copyInvoicesWithArrowConcurrency(
    invoices: List<Invoice>,
    fromBucket: String,
    toBucketName: String,
    dispatcher: CoroutineDispatcher,
    progress: MutableStateFlow<Progress>
): Either<BucketError, List<Unit>> =
    invoices
        .map { invoice -> copyInvoiceDocumentCommand(fromBucket, toBucketName, invoice) }
        .parMapOrAccumulate(context = dispatcher, concurrency = 1000) { command ->
            executeWithProgress(command, progress).bind()
        }.mapLeft {
            CopiesFailures(toBucketName).appendAll(it.toList())
        }


private suspend fun S3ClientWrapper.executeWithProgress(
    command: suspend () -> Either<BucketError, Unit>,
    progress: MutableStateFlow<Progress>
) =
    execute(command)
        .onRight {
            progress.update { it.copy(current = it.current.inc()) }
        }
        .onLeft {
            progress.update { it.copy(current = it.current.inc(), failures = it.failures.inc()) }
        }


context(S3ClientWrapper)
private suspend fun copyInvoices2(
    invoices: List<Invoice>,
    fromBucket: String,
    toBucketName: String,
    dispatcher: CoroutineDispatcher,
    concurrency: Int,
    progress: MutableStateFlow<Progress>,
): Either<BucketError, List<Unit>> =
    invoices.asFlow()
        .map { invoice -> copyInvoiceDocumentCommand(fromBucket, toBucketName, invoice) }
        .concurrentMap(dispatcher, concurrency) {
            either { executeWithProgress(it, progress).bind() }
        }.toList().flattenOrAccumulate().mapLeft {
            CopiesFailures(toBucketName).appendAll(it.toList())
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
    concurrency: Int,
    progress: MutableStateFlow<Progress>,
): Either<CopiesFailures, List<Unit>> =
    invoices
        .map { invoice -> copyInvoiceDocumentCommand(fromBucket, toBucketName, invoice) }
        .chunked(Integer.max(round(invoices.size / concurrency.toFloat()).toInt(), 1)).asFlow() // batch invoices command
        .flatMapMerge(concurrency) { copyCommands ->
            copyCommands.asFlow().map { either { executeWithProgress(it, progress).bind() } }
        }
        .flowOn(dispatcher)
        .toList().flattenOrAccumulate().mapLeft {
            CopiesFailures(toBucketName).appendAll(it.toList())
        }
