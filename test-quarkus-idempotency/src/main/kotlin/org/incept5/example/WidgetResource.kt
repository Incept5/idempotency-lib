package org.incept5.example

import jakarta.transaction.Transactional
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.QueryParam
import org.incept5.idempotency.service.IdempotencyChecker
import org.incept5.idempotency.service.IdempotencyHasher
import org.incept5.idempotency.service.IdempotencyOptions
import org.incept5.idempotency.service.IdempotencyRetry

@Path("/widgets")
class WidgetResource(
    private val widgetService: WidgetService,
    private val idempotencyChecker: IdempotencyChecker
) {
    /**
     * retry=true opts in to IdempotencyRetry.unlessRejected; inTx=true writes SUCCESSFUL in the operation's transaction.
     * Neither set means the default (1.0.13) behaviour.
     */
    @POST
    fun createWidget(
        request: ExampleRequest,
        @QueryParam("retry") retry: Boolean,
        @QueryParam("inTx") inTx: Boolean,
    ): WidgetResult {
        val command = ExampleCommand(request.orgId!!, request.name!!)
        val options = IdempotencyOptions(
            retryOn = if (retry) IdempotencyRetry.unlessRejected else IdempotencyRetry.never,
            successInOperationTransaction = inTx,
        )
        return idempotencyChecker.ensureIdempotency<WidgetResult>(command.orgId, IdempotencyHasher.hashRequest(command), options) {
            widgetService.create(command)
        }
    }

    /**
     * Misuse: in-transaction success from inside a caller's transaction, which the checker must refuse
     */
    @POST
    @Path("/in-caller-transaction")
    @Transactional
    fun createWidgetInCallerTransaction(request: ExampleRequest): WidgetResult {
        val command = ExampleCommand(request.orgId!!, request.name!!)
        return idempotencyChecker.ensureIdempotency<WidgetResult>(
            command.orgId,
            IdempotencyHasher.hashRequest(command),
            IdempotencyOptions(successInOperationTransaction = true),
        ) {
            widgetService.create(command)
        }
    }
}
