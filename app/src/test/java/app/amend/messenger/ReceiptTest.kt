package app.amend.messenger

import app.amend.messenger.data.ChatMessage
import app.amend.messenger.data.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiptTest {
    @Test
    fun statusOnlyMovesForward() {
        assertEquals(MessageStatus.SENT, MessageStatus.SENDING.advance(MessageStatus.SENT))
        assertEquals(MessageStatus.DELIVERED, MessageStatus.SENT.advance(MessageStatus.DELIVERED))
        assertEquals(MessageStatus.SEEN, MessageStatus.DELIVERED.advance(MessageStatus.SEEN))
        assertEquals(MessageStatus.SEEN, MessageStatus.SEEN.advance(MessageStatus.SENT))       // late "sent" ignored
        assertEquals(MessageStatus.SEEN, MessageStatus.SEEN.advance(MessageStatus.DELIVERED))  // receipts out of order
        assertEquals(MessageStatus.SENT, MessageStatus.FAILED.advance(MessageStatus.SENT))     // retry worked
        assertEquals(MessageStatus.DELIVERED, MessageStatus.FAILED.advance(MessageStatus.DELIVERED))
    }

    @Test
    fun receiptLevelIsSaved() {
        val m = ChatMessage("id", "peer", false, "hi", 1, receipt = 2)
        assertEquals(2, ChatMessage.fromMap(m.toMap()).receipt)
        assertEquals(0, ChatMessage.fromMap(m.copy(receipt = 0).toMap()).receipt)
    }
}
