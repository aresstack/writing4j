package com.aresstack.writing4j.book;

import com.aresstack.knowledge4j.basket.OrderingHint;
import com.aresstack.knowledge4j.model.chat.ChatMessage;
import com.aresstack.knowledge4j.model.chat.MainModelChat;
import com.aresstack.knowledge4j.model.chat.MainModelChatResult;
import com.aresstack.knowledge4j.scope.Fence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The outline of the book from the cornerstones the reader chose (M24, 27.11.2026): one call orders them into chapters
 * in an order a newcomer understands and names per chapter the questions the book must answer there, each question with
 * the numbers of the cornerstones it rests on. One call for all instead of questions per cornerstone, because those
 * came out by the hundred and many nearly the same (M19: 247); this way every model measured gave no near duplicates.
 * The answer is plain text in the measured form ({@code Kapitel 1: Titel}, {@code - Frage [3, 17]}); all three models
 * kept it. When the cornerstones do not fit the model's context in one call, they go in parts by topic field and the
 * chapters of the parts follow one another; the limit comes from the model's context length, not from a fixed number.
 * The reader's ordering hints go along as a non-binding hint. The critical and evaluative questions do not come from
 * here (none of the models asked them); they come from the mission ({@link StockQuestions#draftMission}).
 */
public final class CornerstoneOutline {

    /** The settings. */
    public static final class Options {
        public static final int DEFAULT_PLACE_CHARS = 240;
        public static final int DEFAULT_ANSWER_TOKENS = 4000;
        public static final int DEFAULT_CONTEXT_TOKENS = 8192;
        public static final double DEFAULT_CHARS_PER_TOKEN = 3.0;
        public static final int DEFAULT_PARALLEL = 1;

        private final int placeChars;
        private final int answerTokens;
        private final int contextTokens;
        private final double charsPerToken;
        private final int parallel;

        /**
         * @param placeChars    characters of the one place per cornerstone (around the word, anchors first)
         * @param answerTokens  the answer's budget
         * @param contextTokens the context when the model does not tell its own
         * @param charsPerToken characters per token to estimate whether the prompt fits
         * @param parallel      parts at once when the cornerstones go in parts
         */
        public Options(int placeChars, int answerTokens, int contextTokens, double charsPerToken, int parallel) {
            if (placeChars < 1 || answerTokens < 1 || contextTokens < 1 || charsPerToken <= 0 || parallel < 1) {
                throw new IllegalArgumentException("positive settings expected");
            }
            this.placeChars = placeChars;
            this.answerTokens = answerTokens;
            this.contextTokens = contextTokens;
            this.charsPerToken = charsPerToken;
            this.parallel = parallel;
        }

        public static Options defaults() {
            return new Options(DEFAULT_PLACE_CHARS, DEFAULT_ANSWER_TOKENS, DEFAULT_CONTEXT_TOKENS,
                    DEFAULT_CHARS_PER_TOKEN, DEFAULT_PARALLEL);
        }

        public Options withParallel(int value) {
            return new Options(placeChars, answerTokens, contextTokens, charsPerToken, Math.max(1, value));
        }

        public int getPlaceChars() {
            return placeChars;
        }
    }

    /** A cornerstone as the outline sees it: its term, its topic field (empty without) and one place. */
    public static final class Cornerstone {
        private final String term;
        private final String field;
        private final String place;

        public Cornerstone(String term, String field, String place) {
            this.term = term;
            this.field = field == null ? "" : field;
            this.place = place == null ? "" : place;
        }

        public String getTerm() {
            return term;
        }
    }

    /** A question of a chapter with the cornerstones it rests on (positions in the given list, from 0). */
    public static final class Question {
        private final String text;
        private final List<Integer> cornerstones;

        Question(String text, List<Integer> cornerstones) {
            this.text = text;
            this.cornerstones = Collections.unmodifiableList(new ArrayList<Integer>(cornerstones));
        }

        public String getText() {
            return text;
        }

        public List<Integer> getCornerstones() {
            return cornerstones;
        }
    }

    /** A chapter with its questions. */
    public static final class Chapter {
        private final String title;
        private final List<Question> questions;

        Chapter(String title, List<Question> questions) {
            this.title = title;
            this.questions = Collections.unmodifiableList(new ArrayList<Question>(questions));
        }

        public String getTitle() {
            return title;
        }

        public List<Question> getQuestions() {
            return questions;
        }
    }

    /** The outline: chapters in order, and why a part failed (empty when none did). */
    public static final class Result {
        private final List<Chapter> chapters;
        private final List<String> problems;

        Result(List<Chapter> chapters, List<String> problems) {
            this.chapters = Collections.unmodifiableList(new ArrayList<Chapter>(chapters));
            this.problems = Collections.unmodifiableList(new ArrayList<String>(problems));
        }

        public List<Chapter> getChapters() {
            return chapters;
        }

        public List<String> getProblems() {
            return problems;
        }
    }

    static final int ATTEMPTS = 2;
    private static final Pattern CHAPTER = Pattern.compile(
            "^\\s*(?:#+\\s*)?\\**\\s*(?:Kapitel|Chapter)\\s*\\d+\\s*[:.)\\-–]\\s*(.+?)\\s*\\**\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern QUESTION = Pattern.compile("^\\s*(?:[-*•]|\\d+[.)])\\s+(.+?)\\s*$");
    private static final Pattern NUMBERS = Pattern.compile("\\[([\\d,;\\s]*)]\\s*\\.?\\s*$");

    private final MainModelChat chat;
    private final Fence fence;
    private final boolean german;
    private final Options options;
    private final Consumer<String> log;

    public CornerstoneOutline(MainModelChat chat, Fence fence, String language, Options options,
                              Consumer<String> log) {
        if (chat == null) {
            throw new IllegalArgumentException("the outline needs a model");
        }
        this.chat = chat;
        this.fence = fence == null ? Fence.of("") : fence;
        this.german = !"en".equalsIgnoreCase(language == null ? "" : language.trim());
        this.options = options == null ? Options.defaults() : options;
        this.log = log == null ? line -> {
        } : log;
    }

    /**
     * The outline of the given cornerstones; questions name them by their position in this list.
     *
     * @param hints the reader's ordering hints, a non-binding hint for the order
     */
    public Result outline(final List<Cornerstone> cornerstones, final List<OrderingHint> hints) {
        if (cornerstones.isEmpty()) {
            return new Result(Collections.<Chapter>emptyList(), Collections.<String>emptyList());
        }
        final List<List<Integer>> parts = parts(cornerstones, hints);
        List<Chapter> chapters = new ArrayList<Chapter>();
        List<String> problems = new ArrayList<String>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(options.parallel, parts.size()));
        try {
            List<Future<Object[]>> running = new ArrayList<Future<Object[]>>();
            for (final List<Integer> part : parts) {
                running.add(pool.submit(new Callable<Object[]>() {
                    @Override
                    public Object[] call() {
                        return part(cornerstones, part, hints);
                    }
                }));
            }
            Set<String> seen = new LinkedHashSet<String>();
            for (Future<Object[]> future : running) {
                Object[] done;
                try {
                    done = future.get();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    problems.add("interrupted");
                    break;
                } catch (ExecutionException failed) {
                    problems.add("failed: " + failed.getCause());
                    continue;
                }
                @SuppressWarnings("unchecked")
                List<Chapter> got = (List<Chapter>) done[0];
                for (Chapter chapter : got) {
                    List<Question> kept = new ArrayList<Question>();
                    for (Question question : chapter.questions) {
                        if (seen.add(question.text.toLowerCase(Locale.ROOT))) {
                            kept.add(question);
                        }
                    }
                    if (!kept.isEmpty()) {
                        chapters.add(new Chapter(chapter.title, kept));
                    }
                }
                if (!((String) done[1]).isEmpty()) {
                    problems.add((String) done[1]);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        int questions = 0;
        for (Chapter chapter : chapters) {
            questions += chapter.questions.size();
        }
        log.accept("outline: " + chapters.size() + " chapters, " + questions + " questions from "
                + cornerstones.size() + " cornerstones in " + parts.size() + " part(s)");
        return new Result(chapters, problems);
    }

    /** All in one part when the prompt fits; else by topic field, a field too large for one part split further. */
    List<List<Integer>> parts(List<Cornerstone> cornerstones, List<OrderingHint> hints) {
        List<Integer> all = new ArrayList<Integer>();
        for (int i = 0; i < cornerstones.size(); i++) {
            all.add(i);
        }
        if (fits(cornerstones, all, hints)) {
            return Collections.singletonList(all);
        }
        Map<String, List<Integer>> byField = new LinkedHashMap<String, List<Integer>>();
        for (int i = 0; i < cornerstones.size(); i++) {
            String field = cornerstones.get(i).field;
            if (!byField.containsKey(field)) {
                byField.put(field, new ArrayList<Integer>());
            }
            byField.get(field).add(i);
        }
        List<List<Integer>> out = new ArrayList<List<Integer>>();
        List<Integer> current = new ArrayList<Integer>();
        for (List<Integer> field : byField.values()) {
            List<Integer> joined = new ArrayList<Integer>(current);
            joined.addAll(field);
            if (fits(cornerstones, joined, hints)) {
                current = joined;
                continue;
            }
            if (!current.isEmpty()) {
                out.add(current);
                current = new ArrayList<Integer>();
            }
            for (int index : field) {
                List<Integer> more = new ArrayList<Integer>(current);
                more.add(index);
                if (current.isEmpty() || fits(cornerstones, more, hints)) {
                    current = more;
                } else {
                    out.add(current);
                    current = new ArrayList<Integer>(Collections.singletonList(index));
                }
            }
        }
        if (!current.isEmpty()) {
            out.add(current);
        }
        return out;
    }

    private boolean fits(List<Cornerstone> cornerstones, List<Integer> part, List<OrderingHint> hints) {
        int context = chat.contextTokens() > 0 ? chat.contextTokens() : options.contextTokens;
        double tokens = prompt(cornerstones, part, hints).length() / options.charsPerToken;
        return tokens + options.answerTokens <= context;
    }

    /** One call (one more when the answer has no chapter); {chapters, problem}. */
    private Object[] part(List<Cornerstone> cornerstones, List<Integer> part, List<OrderingHint> hints) {
        String prompt = prompt(cornerstones, part, hints);
        String problem = "";
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            MainModelChatResult result = chat.complete(Arrays.asList(ChatMessage.user(prompt)), 0.0,
                    options.answerTokens);
            if (!result.isOk()) {
                problem = "model call failed (" + result.getStatus() + "): " + result.getDetail();
                continue;
            }
            List<Chapter> chapters = parse(result.getText(), part);
            if (chapters.isEmpty()) {
                problem = "the answer has no chapter with questions";
                continue;
            }
            return new Object[]{chapters, ""};
        }
        log.accept("outline part failed: " + problem);
        return new Object[]{Collections.<Chapter>emptyList(), problem};
    }

    /** The prompt as measured in M24, with the cornerstones of the part numbered from 1. */
    String prompt(List<Cornerstone> cornerstones, List<Integer> part, List<OrderingHint> hints) {
        StringBuilder text = new StringBuilder();
        String mission = fence.getMission().trim();
        List<String> hintLines = hintLines(cornerstones, part, hints);
        if (german) {
            text.append("Du hilfst bei der Vorbereitung eines Sachbuchs.\n\n");
            text.append("Rahmen des Buches: ").append(mission).append("\n");
            if (!fence.getExcluded().isEmpty()) {
                text.append("Nicht Gegenstand des Buches: ").append(join(fence.getExcluded())).append("\n");
            }
            text.append("Der Leser des Buches kennt das Thema noch nicht.\n\n");
            text.append("Unten stehen ").append(part.size()).append(" nummerierte Eckpfeiler, die aus einer "
                    + "Quellensammlung ausgewählt wurden, jeder mit einer Fundstelle.\n\n");
            text.append("Aufgabe: Bilde daraus die Gliederung des Buches.\n"
                    + "- Ordne die Eckpfeiler zu Kapiteln in einer Reihenfolge, in der ein Neuling sie versteht: Was "
                    + "Voraussetzung für anderes ist, kommt früher.\n"
                    + "- Formuliere zu jedem Kapitel die Fragen, die das Buch dort beantworten muss. Jede Frage muss "
                    + "ohne die Fundstellen verständlich sein und sich auf den Gegenstand des Buches beziehen.\n"
                    + "- Fasse Fragen zusammen, die dasselbe wissen wollen. Keine Frage doppelt.\n"
                    + "- Gib hinter jeder Frage in eckigen Klammern die Nummern der Eckpfeiler an, auf die sie sich "
                    + "stützt.\n\n");
            if (!hintLines.isEmpty()) {
                text.append("Der Leser hat einige Eckpfeiler so geordnet (ein Hinweis, keine Vorgabe):\n");
                for (String line : hintLines) {
                    text.append("- ").append(line).append("\n");
                }
                text.append("\n");
            }
            text.append("Antworte nur in dieser Form und ohne weitere Erklärung:\n"
                    + "Kapitel 1: <Titel>\n- <Frage> [3, 17]\n- <Frage> [8]\nKapitel 2: <Titel>\n...\n\n"
                    + "Eckpfeiler:\n");
        } else {
            text.append("You help prepare a non-fiction book.\n\n");
            text.append("Frame of the book: ").append(mission).append("\n");
            if (!fence.getExcluded().isEmpty()) {
                text.append("Not the subject of the book: ").append(join(fence.getExcluded())).append("\n");
            }
            text.append("The reader of the book does not know the subject yet.\n\n");
            text.append("Below are ").append(part.size()).append(" numbered cornerstones chosen from a collection "
                    + "of sources, each with one place.\n\n");
            text.append("Task: build the outline of the book from them.\n"
                    + "- Order the cornerstones into chapters in an order a newcomer understands: what others build "
                    + "on comes first.\n"
                    + "- For every chapter, write the questions the book must answer there. Every question must be "
                    + "understandable without the places and refer to the subject of the book.\n"
                    + "- Merge questions that want to know the same. No question twice.\n"
                    + "- After every question, give the numbers of the cornerstones it rests on in square "
                    + "brackets.\n\n");
            if (!hintLines.isEmpty()) {
                text.append("The reader ordered some cornerstones like this (a hint, not a rule):\n");
                for (String line : hintLines) {
                    text.append("- ").append(line).append("\n");
                }
                text.append("\n");
            }
            text.append("Answer only in this form and without further explanation:\n"
                    + "Chapter 1: <title>\n- <question> [3, 17]\n- <question> [8]\nChapter 2: <title>\n...\n\n"
                    + "Cornerstones:\n");
        }
        for (int i = 0; i < part.size(); i++) {
            Cornerstone cornerstone = cornerstones.get(part.get(i));
            text.append(i + 1).append(". ").append(cornerstone.term).append(": ")
                    .append(cornerstone.place.replaceAll("\\s+", " ").trim()).append("\n");
        }
        return text.toString();
    }

    private List<String> hintLines(List<Cornerstone> cornerstones, List<Integer> part, List<OrderingHint> hints) {
        List<String> out = new ArrayList<String>();
        if (hints == null) {
            return out;
        }
        Set<String> terms = new LinkedHashSet<String>();
        for (int index : part) {
            terms.add(cornerstones.get(index).term.toLowerCase(Locale.ROOT));
        }
        for (OrderingHint hint : hints) {
            if (terms.contains(hint.term().toLowerCase(Locale.ROOT))
                    && terms.contains(hint.parent().toLowerCase(Locale.ROOT))) {
                out.add(german ? hint.term() + " unter " + hint.parent() : hint.term() + " under " + hint.parent());
            }
        }
        return out;
    }

    /** The chapters of an answer; numbers outside the part are dropped, a question without any keeps none. */
    static List<Chapter> parse(String answer, List<Integer> part) {
        List<Chapter> out = new ArrayList<Chapter>();
        String title = null;
        List<Question> questions = new ArrayList<Question>();
        for (String line : (answer == null ? "" : answer).split("\\R")) {
            Matcher chapter = CHAPTER.matcher(line);
            if (chapter.matches()) {
                if (title != null && !questions.isEmpty()) {
                    out.add(new Chapter(title, questions));
                }
                title = chapter.group(1).replace("*", "").trim();
                questions = new ArrayList<Question>();
                continue;
            }
            Matcher question = QUESTION.matcher(line);
            if (title == null || !question.matches()) {
                continue;
            }
            String text = question.group(1).replace("*", "").trim();
            List<Integer> numbers = new ArrayList<Integer>();
            Matcher bracket = NUMBERS.matcher(text);
            if (bracket.find()) {
                for (String number : bracket.group(1).split("[,;\\s]+")) {
                    if (number.isEmpty()) {
                        continue;
                    }
                    int at = Integer.parseInt(number) - 1;
                    if (at >= 0 && at < part.size() && !numbers.contains(part.get(at))) {
                        numbers.add(part.get(at));
                    }
                }
                text = text.substring(0, bracket.start()).trim();
            }
            if (!text.isEmpty()) {
                questions.add(new Question(text, numbers));
            }
        }
        if (title != null && !questions.isEmpty()) {
            out.add(new Chapter(title, questions));
        }
        return out;
    }

    private static String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            out.append(out.length() == 0 ? "" : "; ").append(value);
        }
        return out.toString();
    }
}
