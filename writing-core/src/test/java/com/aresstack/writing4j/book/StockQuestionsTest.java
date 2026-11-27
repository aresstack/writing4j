package com.aresstack.writing4j.book;

import com.aresstack.knowledge4j.scope.Fence;
import com.aresstack.knowledge4j.model.chat.ChatMessage;
import com.aresstack.knowledge4j.model.chat.MainModelChat;
import com.aresstack.knowledge4j.model.chat.MainModelChatResult;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The questions from mission and cornerstones and their check against mission and fence. Made-up texts (city traffic). */
public class StockQuestionsTest {

    private static final Fence FENCE = new Fence("Ein Sachbuch für Stadtplaner über Verkehrssicherheit "
            + "in Innenstädten.", "Nur der Verkehr in der Stadt.", Collections.singletonList("Tempo 30"),
            Collections.singletonList("Autobahnen"), "Deutschland", "");

    @Test
    public void theDraftOfACornerstoneShowsFenceTermAndPlacesAndNoConcept() {
        Answers chat = new Answers("{\"fragen\":[{\"frage\":\"Wie wirkt Tempo 30 auf Unfälle?\",\"belege\":[1, 7]},"
                + "{\"frage\":\" Wie wirkt Tempo 30 auf Unfälle? \",\"belege\":[]},{\"frage\":\"\",\"belege\":[]},"
                + "{\"frage\":\"Wer ordnet Tempo 30 an?\",\"belege\":[1]},"
                + "{\"frage\":\"Eine dritte Frage?\",\"belege\":[1]}]}");
        StockQuestions.Drafted drafted = new StockQuestions(chat, FENCE, "de", null, null).draft("eckpfeiler-1",
                "Tempo 30", Arrays.asList(new StockQuestions.Passage("p1", "Bericht > Unfälle", "Nach Tempo 30 sank "
                        + "die Zahl schwerer Unfälle."), new StockQuestions.Passage("p2", "", "")), 2);

        assertEquals("Tempo 30", drafted.getTitle());
        assertEquals("the same question once, none empty, at most two", Arrays.asList("Wie wirkt Tempo 30 auf "
                + "Unfälle?", "Wer ordnet Tempo 30 an?"), drafted.getQuestions());
        assertEquals("only places that were shown", Collections.singletonList("p1"), drafted.getEvidence().get(0));
        String prompt = chat.prompts.get(0);
        assertTrue(prompt, prompt.startsWith("Buchauftrag:\nEin Sachbuch für Stadtplaner"));
        assertTrue(prompt, prompt.contains("Ausgeschlossen:\n- Autobahnen"));
        assertTrue(prompt, prompt.contains("Eckpfeiler des Buchs: Tempo 30\n"));
        assertTrue(prompt, prompt.contains("Höchstens 2 Fragen"));
        assertTrue(prompt, prompt.contains("[1] Überschrift: Bericht > Unfälle\n\"\"\"\nNach Tempo 30 sank"));
        assertFalse("an empty passage is not shown", prompt.contains("[2]"));
        assertFalse("no concept", prompt.contains("Karte") || prompt.contains("Mindmap"));
    }

    @Test
    public void theQuestionsOfACornerstoneAreNamedStringsAsWell() {
        Answers chat = new Answers("{\"fragen\":[\"Wie wirkt Tempo 30 auf Unfälle?\"]}");
        StockQuestions.Drafted drafted = new StockQuestions(chat, FENCE, "de", null, null).draft("eckpfeiler-1",
                "Tempo 30", Collections.singletonList(new StockQuestions.Passage("p1", "", "Tempo 30 wirkt.")), 2);
        assertEquals(Collections.singletonList("Wie wirkt Tempo 30 auf Unfälle?"), drafted.getQuestions());
        assertTrue(drafted.getEvidence().get(0).isEmpty());
    }

    @Test
    public void theCornerstoneShowsAsManyPlacesAsTheContextHolds() {
        StockQuestions questions = new StockQuestions(new Answers(), FENCE, "de",
                StockQuestions.Options.defaults().withContextTokens(3000), null);
        List<StockQuestions.Passage> passages = new ArrayList<StockQuestions.Passage>();
        for (int i = 0; i < 12; i++) {
            passages.add(new StockQuestions.Passage("p" + i, "", repeat("Ein langer Satz über den Verkehr. ", 40)));
        }
        List<StockQuestions.Passage> shown = questions.fitting(passages);
        assertFalse(shown.isEmpty());
        assertTrue(shown.size() < 12);
        assertEquals("p0", shown.get(0).getPassageId());
    }

    @Test
    public void theMissionQuestionsComeFromTheFenceAlone() {
        Answers chat = new Answers("{\"titel\":\"Sichere Innenstädte\",\"fragen\":[\"Wie wirkt Tempo 30?\"]}");
        StockQuestions.Drafted demanded = new StockQuestions(chat, FENCE, "de", null, null).draftMission();

        assertEquals(Collections.singletonList("Wie wirkt Tempo 30?"), demanded.getQuestions());
        assertTrue(chat.systems.get(0).startsWith("Du planst ein Sachbuch"));
        assertTrue(chat.prompts.get(0).contains("Rand des Themas:\nNur der Verkehr in der Stadt."));
        assertTrue(chat.prompts.get(0).contains("Zusammen decken die Fragen alles ab, was der Auftrag verlangt"));
    }

    @Test
    public void theCheckAsksAMissedQuestionOnceMoreAndNeverDropsOne() {
        Answers chat = new Answers(
                "{\"urteile\":[{\"nr\":\"F1\",\"grund\":\"Gehört dazu.\",\"urteil\":\"INNEN\"},"
                        + "{\"nr\":\"2\",\"grund\":\"Autobahnen sind ausgeschlossen.\",\"urteil\":\"AUSSEN\"}]}",
                "kein JSON");
        List<StockQuestions.Verdict> verdicts = new StockQuestions(chat, FENCE, "de", null, null).check(Arrays.asList(
                "Wie wirkt Tempo 30?", "Wie sicher sind Autobahnen?", "Was kostet ein Zebrastreifen?"));

        assertEquals(3, verdicts.size());
        assertEquals(StockQuestions.Relation.INNEN, verdicts.get(0).getRelation());
        assertEquals(StockQuestions.Relation.AUSSEN, verdicts.get(1).getRelation());
        assertEquals("missed twice: kept, not checked", StockQuestions.Relation.UNGEPRUEFT,
                verdicts.get(2).getRelation());
        assertEquals(2, chat.prompts.size());
        assertTrue(chat.prompts.get(1), chat.prompts.get(1).contains("[F1] Was kostet ein Zebrastreifen?")
                && !chat.prompts.get(1).contains("Autobahnen?"));
    }

    private static String repeat(String text, int times) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < times; i++) {
            out.append(text);
        }
        return out.toString();
    }

    /** Answers in turn; records system and user prompt of every call. */
    static final class Answers implements MainModelChat {
        private final LinkedList<String> answers;
        final List<String> systems = new ArrayList<String>();
        final List<String> prompts = new ArrayList<String>();

        Answers(String... answers) {
            this.answers = new LinkedList<String>(Arrays.asList(answers));
        }

        @Override
        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            return completeJson(messages, temperature, maxOutputTokens, null);
        }

        @Override
        public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                                String schemaJson) {
            systems.add(messages.get(0).getContent());
            prompts.add(messages.get(messages.size() - 1).getContent());
            return answers.isEmpty() ? MainModelChatResult.ok("{\"titel\":\"\",\"fragen\":[]}")
                    : MainModelChatResult.ok(answers.removeFirst());
        }

        @Override
        public String modelName() {
            return "answers";
        }
    }
}
