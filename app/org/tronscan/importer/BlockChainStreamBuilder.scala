package org.tronscan.importer

import akka.NotUsed
import akka.actor.{ActorContext, ActorSystem}
import akka.event.EventStream
import akka.stream.scaladsl.{Flow, Source}
import org.tron.api.api.WalletGrpc.WalletStub
import org.tron.api.api.WalletSolidityGrpc.WalletSolidityStub
import org.tron.api.api.{BlockLimit, NumberMessage}
import org.tron.protos.Tron.{Block, Transaction}
import org.tron.protos.Tron.Transaction.Contract.ContractType.{AssetIssueContract, ParticipateAssetIssueContract, TransferAssetContract, TransferContract, VoteWitnessContract, WitnessCreateContract}
import org.tronscan.domain.Events._
import org.tronscan.models._
import org.tronscan.utils.ProtoUtils

import scala.concurrent.{ExecutionContext, Future}

class BlockChainStreamBuilder {

  /**
    * Reads the blocks with getBlockByNum
    */
  def readSolidityBlocks(from: Long, to: Long, parallel: Int = 12)(client: WalletSolidityStub): Source[Block, NotUsed] = {
    Source(from to to)
      .mapAsync(parallel) { i => client.getBlockByNum(NumberMessage(i)) }
      .filter(_.blockHeader.isDefined)
  }

  /**
    * Reads the blocks with getBlockByNum
    */
  def readFullNodeBlocks(from: Long, to: Long, parallel: Int = 12)(client: WalletStub): Source[Block, NotUsed] = {
    Source(from to to)
      .mapAsync(parallel) { i => client.getBlockByNum(NumberMessage(i)) }
      .filter(_.blockHeader.isDefined)
  }

  /**
    * Reads all the blocks using batch calls
    */
  def readFullNodeBlocksBatched(from: Long, to: Long, batchSize: Int = 50)(client: WalletStub)(implicit executionContext: ExecutionContext): Source[Block, NotUsed] = {

    Source.unfoldAsync(from) { prev =>

      if (prev < to) {

        val toBlock = if (prev + batchSize > to) to else prev + batchSize

        client
          .getBlockByLimitNext(BlockLimit(prev, toBlock))
          .map { blocks =>
            Some((toBlock, blocks.block.filter(_.blockHeader.isDefined).sortBy(_.getBlockHeader.getRawData.number)))
          }
      } else {
        Future.successful(None)
      }
    }
      .flatMapConcat(x => Source(x.toList))
  }

  def filterContracts(contractTypes: List[Transaction.Contract.ContractType]) = {
    Flow[Transaction.Contract]
      .filter(contract  => contractTypes.contains(contract.`type`))
  }

  /**
    * Publishes contracts to the given eventstream
    */
  def publishContractEvents(contractTypes: List[Transaction.Contract.ContractType])(implicit context: ActorContext) = {
    val eventStream = context.system.eventStream
    Flow[Transaction.Contract]
      .filter(contract  => contractTypes.contains(contract.`type`))
      .map { contract =>
        (contract.`type`, ProtoUtils.fromContract(contract)) match {
          case (TransferContract, transfer: TransferModel) =>
            eventStream.publish(TransferCreated(transfer))

          case (TransferAssetContract, transfer: TransferModel) =>
            eventStream.publish(AssetTransferCreated(transfer))

          case (WitnessCreateContract, witness: WitnessModel) =>
            eventStream.publish(WitnessCreated(witness))

          case (VoteWitnessContract, votes: VoteWitnessList) =>
            votes.votes.foreach { vote =>
              eventStream.publish(VoteCreated(vote))
            }

          case (AssetIssueContract, assetIssue: AssetIssueContractModel) =>
            eventStream.publish(AssetIssueCreated(assetIssue))

          case (ParticipateAssetIssueContract, participate: ParticipateAssetIssueModel) =>
            eventStream.publish(ParticipateAssetIssueModelCreated(participate))

          case _ =>
        }

        contract
      }
  }
}