import com.knightdx.glassestunes.*;
import java.util.*;

/** Runs the app's parsing/matching code on a real Android runtime (ICU regex), where the JVM tests can't reach. */
public class DeviceCheck {
    static int failures = 0;
    static void run(String what, Runnable r) {
        try { r.run(); System.out.println("ok   " + what); }
        catch (Throwable t) { failures++; System.out.println("FAIL " + what + " -> " + t); }
    }
    public static void main(String[] a) {
        String[] phrases = {
            "play Queen", "play Bohemian Rhapsody by Queen", "play the album Thriller", "play the Thriller album",
            "play my workout playlist", "play songs by Drake", "shuffle Drake", "play the album abbey road on shuffle",
            "play genre jazz", "shuffle everything", "pause", "resume", "next", "go back", "volume up", "what's playing",
            "open Maps", "launch the Samsung Notes app", "talk to Gemini", "hey google", "call Mom",
            "call John Smith on his cell", "give Sarah a call", "dial 555-123-4567", "answer the call", "decline",
            "text Mom I'm on my way", "send a text to mom saying I'll be home now", "tell John that dinner is ready",
            "let mom know I'm running late", "send Sarah a text saying happy birthday", "text mom",
            "WhatsApp John see you soon", "message mom on WhatsApp saying hi", "send a WhatsApp to John",
            "reply", "reply sounds good", "reply to John ok see you", "reply to John saying on my way",
            "read my messages", "what did Sarah say", "read messages from John", "read John's messages",
            "tap the Send button", "click on settings", "scroll down", "type hello there", "go home", "press back",
            "press play", "can you play some Beyonce on Samsung Music", "random nonsense words",
            "google who won the lakers game", "look up how tall is the Eiffel Tower", "play mister brightside from the killers",
            "play sweet child of mine by guns and roses", "how long do I boil an egg",
        };
        for (String p : phrases) run("parse \"" + p + "\"", () -> System.out.println("       -> " + CommandParser.INSTANCE.parse(p)));
        run("isYes", () -> CommandParser.INSTANCE.isYes("Yeah, send it."));
        List<Track> lib = Arrays.asList(
            new Track(1, "Bohemian Rhapsody", "Queen", "A Night at the Opera [Remastered]", 11, ""),
            new Track(2, "God's Plan (feat. Someone)", "Drake", "Scorpion", 5, ""),
            new Track(3, "Thriller", "Michael Jackson", "Thriller", 4, "Pop"));
        run("library match", () -> System.out.println("       -> " + LibraryMatcher.INSTANCE.select(lib, new PlayRequest("queen", Focus.ANY, null, false), kotlin.random.Random.Default)));
        run("library normalize", () -> System.out.println("       -> " + LibraryMatcher.INSTANCE.normalize("A Night at the Opera [Remastered] (feat. X) & more")));
        List<Contact> contacts = Arrays.asList(new Contact(1, "Mom", Arrays.asList(new Phone("555", "mobile", false)), null),
            new Contact(2, "John Smith", Arrays.asList(new Phone("556", "mobile", true)), "1556@s.whatsapp.net"));
        run("contact split", () -> System.out.println("       -> " + ContactMatcher.INSTANCE.split(contacts, "john smith see you at 5")));
        run("contact number", () -> ContactMatcher.INSTANCE.asPhoneNumber("+1 555-1234"));
        run("app match", () -> AppMatcher.INSTANCE.find(Arrays.asList(new AppEntry("Maps", "m")), "google maps"));
        run("jarvis grammar", () -> System.out.println("       -> " + JarvisDetector.INSTANCE.getGRAMMAR().substring(0, 40)));
        run("jarvis wake", () -> {
            boolean yes = JarvisDetector.INSTANCE.isWake("{\n  \"result\" : [{\n \"conf\" : 0.93,\n \"end\" : 1.2,\n \"start\" : 0.6,\n \"word\" : \"jarvis\"\n }],\n  \"text\" : \"jarvis\"\n}", 0.5);
            if (!yes) throw new AssertionError("should wake");
        });
        run("fuzzy song match", () -> {
            List<Track> songs = Arrays.asList(new Track(9, "Mr. Brightside - 2004 Remaster", "The Killers", "Hot Fuss", 2, ""),
                new Track(10, "Halo", "Beyoncé", "I Am", 2, ""));
            Selection s1 = LibraryMatcher.INSTANCE.select(songs, new PlayRequest("mister brightside", Focus.SONG, "the killers", false), kotlin.random.Random.Default);
            Selection s2 = LibraryMatcher.INSTANCE.select(songs, new PlayRequest("halo", Focus.SONG, "beyonce", false), kotlin.random.Random.Default);
            if (s1 == null || s1.getTracks().get(0).getId() != 9 || s2 == null || s2.getTracks().get(0).getId() != 10) throw new AssertionError(s1 + " / " + s2);
        });
        run("spoken answer cleanup", () -> System.out.println("       -> " + SpokenText.INSTANCE.clean("**The Lakers** won [1]. See https://x.y", 450)));
        run("glasses names", () -> GlassesNames.INSTANCE.isGlasses("Ray-Ban Meta 04B2"));
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
    }
}
