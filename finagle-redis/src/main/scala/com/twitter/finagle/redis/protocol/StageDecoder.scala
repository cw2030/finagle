package com.twitter.finagle.redis.protocol

import com.twitter.finagle.util.BufReader
import com.twitter.io.Buf
import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer

/**
 * Thread-safe, stateful, asynchronous Redis decoder.
 */
private[redis] class StageDecoder(init: Stage) {

  private[this] class Acc(
      var n: Long,
      val replies: ListBuffer[Reply],
      val finish: List[Reply] => Reply)

  import Stage._

  private[this] var reader = BufReader(Buf.Empty)
  private[this] var stack = List.empty[Acc]
  private[this] var current = init

  final def absorb(buf: Buf): Reply = synchronized {
    // Absorb the new buffer.
    reader = BufReader(reader.readAll().concat(buf))

    // Decode the next reply if possible.
    decodeNext(current)
  }

  // Tries its best to decode the next _full_ reply or returns `null` if
  // there is not enough data in the input buffer.
  @tailrec
  private[this] def decodeNext(stage: Stage): Reply = stage(reader) match {
    case NextStep.Incomplete =>
      // The decoder is starving so we capture the current state
      // and fail-fast with `null`.
      current = stage
      null
    case NextStep.Goto(nextStage) => decodeNext(nextStage)
    case NextStep.Emit(reply) =>
      stack match {
        case Nil =>
          // We finish decoding of a single reply so reset the state.
          current = init
          reply
        case acc :: rest if acc.n == 1 =>
          stack = rest
          acc.replies += reply
          decodeNext(Stage.const(NextStep.Emit(acc.finish(acc.replies.toList))))
        case acc :: _ =>
          acc.n -= 1
          acc.replies += reply
          decodeNext(init)
      }
    case NextStep.Accumulate(n, finish) =>
      stack = new Acc(n, ListBuffer.empty[Reply], finish) :: stack
      decodeNext(init)
  }
}
