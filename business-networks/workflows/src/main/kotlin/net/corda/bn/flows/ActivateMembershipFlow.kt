package net.corda.bn.flows

import net.corda.bn.contracts.MembershipContract
import net.corda.bn.states.MembershipStatus
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.CollectSignaturesFlow
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.ReceiveFinalityFlow
import net.corda.core.flows.SignTransactionFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.unwrap

@InitiatingFlow
@StartableByRPC
class ActivateMembershipFlow(val membershipId: UniqueIdentifier) : FlowLogic<SignedTransaction>(), MembershipManagementFlow {

    override fun call(): SignedTransaction {
        val databaseService = serviceHub.cordaService(DatabaseService::class.java)
        val membership = databaseService.getMembership(membershipId)
        val signers = requiredSigners()

        // building transaction
        val builder = TransactionBuilder()
                .addInputState(membership)
                .addOutputState(membership.state.data.copy(status = MembershipStatus.ACTIVE))
                .addCommand(MembershipContract.Commands.Activate(), signers.map { it.owningKey })
        builder.verify(serviceHub)

        // send info to observers whether they need to sign the transaction
        val networkId = membership.state.data.networkId
        val observerSessions = databaseService.getMembersAuthorisedToModifyMembership(networkId).map { initiateFlow(it) }
        observerSessions.forEach { it.send(signers.contains(it.counterparty)) }

        // signing transaction
        val selfSignedTransaction = serviceHub.signInitialTransaction(builder)
        val signerSessions = (signers - ourIdentity).map { initiateFlow(it) }
        val allSignedTransaction = subFlow(CollectSignaturesFlow(selfSignedTransaction, signerSessions))

        // finalise transaction
        return subFlow(FinalityFlow(allSignedTransaction, observerSessions))
    }

    override fun requiredSigners(): List<Party> = emptyList()
}

@InitiatedBy(ActivateMembershipFlow::class)
class ActivateMembershipResponerFlow(val session: FlowSession) : FlowLogic<Unit>() {

    override fun call() {
        val isSigner = session.receive<Boolean>().unwrap { it }

        val stx = if (isSigner) {
            val signResponder = object : SignTransactionFlow(session) {
                override fun checkTransaction(stx: SignedTransaction) {
                    val command = stx.tx.commands.single()
                    if (command.value !is MembershipContract.Commands.Activate) {
                        throw FlowException("Only Activate command is allowed")
                    }

                    stx.toLedgerTransaction(serviceHub, false).verify()
                }
            }
            subFlow(signResponder)
        } else null

        subFlow(ReceiveFinalityFlow(session, stx?.id))
    }
}