package com.knightdx.glassestunes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCommandsTest {
    private fun parse(s: String) = CommandParser.parse(s)

    @Test fun calls() {
        assertEquals(Command.Call("mom"), parse("Call Mom"))
        assertEquals(Command.Call("john smith", "mobile"), parse("call John Smith on his cell"))
        assertEquals(Command.Call("dad", "work"), parse("phone dad at work"))
        assertEquals(Command.Call("sarah"), parse("give Sarah a call"))
        assertEquals(Command.Call("555 123 4567"), parse("dial 555-123-4567"))
        assertEquals(Command.AnswerCall, parse("answer the call"))
        assertEquals(Command.DeclineCall, parse("decline"))
    }

    @Test fun texts() {
        assertEquals(Command.SendMessage(MessageApp.SMS, "mom i'm on my way"), parse("text Mom I'm on my way"))
        assertEquals(Command.SendMessage(MessageApp.SMS, "mom", "i'll be home now"), parse("send a text to mom saying I'll be home now"))
        assertEquals(Command.SendMessage(MessageApp.SMS, "john", "dinner is ready"), parse("tell John that dinner is ready"))
        assertEquals(Command.SendMessage(MessageApp.SMS, "mom", "i'm running late"), parse("let mom know I'm running late"))
        assertEquals(Command.SendMessage(MessageApp.SMS, "sarah", "happy birthday"), parse("send Sarah a text saying happy birthday"))
        assertEquals(Command.SendMessage(MessageApp.SMS, "mom"), parse("text mom"))
    }

    @Test fun whatsapp() {
        assertEquals(Command.SendMessage(MessageApp.WHATSAPP, "john see you soon"), parse("WhatsApp John see you soon"))
        assertEquals(Command.SendMessage(MessageApp.WHATSAPP, "mom", "hi"), parse("message mom on WhatsApp saying hi"))
        assertEquals(Command.SendMessage(MessageApp.WHATSAPP, "john"), parse("send a WhatsApp to John"))
        // "whatsapp" inside the message itself doesn't switch apps.
        assertEquals(Command.SendMessage(MessageApp.SMS, "john", "i'm on whatsapp now"), parse("text john saying I'm on whatsapp now"))
    }

    @Test fun replyAndRead() {
        assertEquals(Command.Reply(), parse("reply"))
        assertEquals(Command.Reply(message = "sounds good with me"), parse("reply sounds good with me"))
        assertEquals(Command.Reply(recipientAndMessage = "john ok see you"), parse("reply to John ok see you"))
        assertEquals(Command.Reply(recipientAndMessage = "john", message = "on my way"), parse("reply to John saying on my way"))
        assertEquals(Command.ReadMessages(), parse("read my messages"))
        assertEquals(Command.ReadMessages("sarah"), parse("what did Sarah say"))
        assertEquals(Command.ReadMessages("john"), parse("read messages from John"))
    }

    @Test fun screen() {
        assertEquals(Command.UiTap("send"), parse("tap the Send button"))
        assertEquals(Command.UiTap("settings"), parse("click on settings"))
        assertEquals(Command.UiScroll(down = true), parse("scroll down"))
        assertEquals(Command.UiType("hello there"), parse("type hello there"))
        assertEquals(Command.UiHome, parse("go home"))
        assertEquals(Command.UiBack, parse("press back"))
        // "go back" stays "previous track".
        assertEquals(Command.Previous, parse("go back"))
    }

    @Test fun musicStillWorks() {
        assertEquals(Command.Play(PlayRequest("call me maybe", Focus.ANY)), parse("play call me maybe"))
        assertEquals(Command.OpenApp("messages"), parse("open messages"))
    }

    @Test fun confirmations() {
        assertTrue(CommandParser.isYes("Yes"))
        assertTrue(CommandParser.isYes("send it"))
        assertTrue(CommandParser.isYes("Yeah, send it."))
        assertFalse(CommandParser.isYes("no"))
        assertFalse(CommandParser.isYes("wait"))
        assertFalse(CommandParser.isYes("yes but change it"))
    }
}

class ContactMatcherTest {
    private val contacts = listOf(
        Contact(1, "Mom", listOf(Phone("+1 555 0100", "mobile"), Phone("555 0199", "home"))),
        Contact(2, "John Smith", listOf(Phone("555 0200", "work"), Phone("555 0201", "mobile", primary = true)), "15550201@s.whatsapp.net"),
        Contact(3, "Johnny Cash", listOf(Phone("555 0300", "mobile"))),
        Contact(4, "Sarah", listOf(Phone("555 0400", "mobile"))),
        Contact(5, "No Number", emptyList()),
    )

    @Test fun findsByName() {
        assertEquals(1L, ContactMatcher.find(contacts, "mom")?.id)
        assertEquals(2L, ContactMatcher.find(contacts, "john smith")?.id)
        assertEquals(2L, ContactMatcher.find(contacts, "john")?.id)
        assertNull(ContactMatcher.find(contacts, "no number"))
        assertNull(ContactMatcher.find(contacts, "bob"))
    }

    @Test fun splitsNameFromMessage() {
        assertEquals(1L to "i'm on my way", ContactMatcher.split(contacts, "mom i'm on my way")!!.let { it.first.id to it.second })
        assertEquals(2L to "see you at 5", ContactMatcher.split(contacts, "john smith see you at 5")!!.let { it.first.id to it.second })
        assertEquals(4L to "call me", ContactMatcher.split(contacts, "sarah call me")!!.let { it.first.id to it.second })
        assertNull(ContactMatcher.split(contacts, "mom"))
        assertNull(ContactMatcher.split(contacts, "bob hi there"))
    }

    @Test fun picksTheRightNumber() {
        assertEquals("555 0199", ContactMatcher.pickNumber(contacts[0], "home")?.number)
        assertEquals("+1 555 0100", ContactMatcher.pickNumber(contacts[0], null)?.number)
        assertEquals("555 0201", ContactMatcher.pickNumber(contacts[1], null)?.number)
    }

    @Test fun phoneNumbers() {
        assertEquals("5551234567", ContactMatcher.asPhoneNumber("555 123 4567"))
        assertEquals("+15551234", ContactMatcher.asPhoneNumber("+1 555-1234"))
        assertNull(ContactMatcher.asPhoneNumber("mom"))
        assertNull(ContactMatcher.asPhoneNumber("route 66"))
    }

    @Test fun sentenceCase() {
        assertEquals("I'm on my way", ContactMatcher.sentence("i'm on my way"))
    }
}
