import kotlinx.coroutines.*
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.flow.*
import kotlin.math.round

@OptIn(FlowPreview::class)
private fun <T, R> Flow<T>.concurrentMap(dispatcher: CoroutineDispatcher, concurrencyLevel: Int, transform: suspend (T) -> R): Flow<R> {
    return flatMapMerge(concurrencyLevel) { value ->
        flow { emit(transform(value)) }
    }.flowOn(dispatcher)
}

@OptIn(FlowPreview::class)
private suspend fun S3Service.copyInvoices3(
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

private suspend fun S3Service.copyInvoices2(
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
            execute { it.invoke() }
        }
        .scan(0) { acc, _ -> acc + 1 }
        .collect {
            if (it % 100 == 0)
                println("${percentageRateWith2digits(it, size)}%")
        }


@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun S3Service.copyInvoiceBaseWithChannel(
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
                    execute { it.invoke() }
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
private suspend fun S3Service.copyInvoiceBaseWithChannel2(
    invoices: List<Invoice>,
    fromBucket: String,
    toBucketName: String,
    dispatcher: CoroutineDispatcher,
    size: Int,
    concurrency: Int,
) {
    supervisorScope() {
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
                        execute { command.invoke() }
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