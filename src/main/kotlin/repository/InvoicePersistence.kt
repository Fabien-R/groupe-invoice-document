package repository

import Invoice
import comgithubfabien.InvoicesQueries
import java.time.OffsetDateTime
import java.util.*

interface InvoicePersistence {
    /** Retrieve all invoices for a specific client between start included and end excluded **/
    fun getAllInvoices(start: OffsetDateTime, end: OffsetDateTime, clientId: UUID): List<Invoice>
}

fun invoicePersistence(
    invoicesQueries: InvoicesQueries
) = object : InvoicePersistence {
    override fun getAllInvoices(start: OffsetDateTime, end: OffsetDateTime, clientId: UUID): List<Invoice> {
        return invoicesQueries.selectAllInvoicesBetweenStartAndEndForClient(
            start,
            end,
            clientId
        ) { client_name, restaurant_name, _, date, supplier_name, reference, document_id, total_inc, original_filename ->
            Invoice(
                client_name!!.onlyKeepAlphaNumeric(),
                restaurant_name!!.onlyKeepAlphaNumeric(),
                date,
                supplier_name!!.onlyKeepAlphaNumeric(),
                reference!!,
                document_id,
                total_inc,
                original_filename!!
            )
        }.executeAsList()
    }
    fun String.onlyKeepAlphaNumeric() = this.lowercase().replace(Regex("[^a-zA-Z0-9]+"), "")
}