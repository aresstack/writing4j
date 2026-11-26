<p align="center"><img src="docs/banner.png" alt="Writing4j: evidence-based writing and document synthesis for Java" width="100%"></p>

# Writing4j

Evidence-based writing and document synthesis for Java. Writing4j turns collected sources and passages into a
structured, cited document: outline planning, evidence assignment, section drafting, revision, citation management,
quality checks and manuscript assembly.

> **Status:** in development on `main`, not released. Writing4j depends on
> [Knowledge4j](https://github.com/aresstack/knowledge4j), which is not released either; build both side by side
> (see *Building*).

## From the basket to the book

The input is mission and Weidezaun (`Fence`), the basket of sources (`SourceBasket`, references only, read through a
`BasketReader`) and the writing settings; nothing else. How the sources were found (a research run of
[Research4j](https://github.com/aresstack/research4j), the user's favourites, a folder) is no input, and no search plan
of the research steers the book. `BookRun` (module `writing-core`) runs:

1. source profiles against the fence; passages outside the fence are set aside before anything else,
2. clustering of the remaining passages into groups,
3. questions from both sides: those the mission demands and those the groups bring up, checked against mission and
   fence,
4. evidence per question,
5. a section plan from the material, the sections written one after another with a writing memory and checked
   sentence by sentence,
6. the mission report: every question answered, only leads, open, a further search needed, or outside the fence.

**Two operating modes, one code.** The host gives the number of concurrent calls its model takes (`capacity`, from
the model port): 1 for a large model alone on one graphics card, many for a cloud service. Every step but the writing
runs that many calls at once; the order of the work stays the same, and the sections are always written in turn.

## Building

```bash
git clone https://github.com/aresstack/knowledge4j
git clone https://github.com/aresstack/writing4j
cd writing4j && ./gradlew build
```

The Composite Build takes Knowledge4j from `../knowledge4j`; another folder is set with `-Pknowledge4j.dir=<folder>`.

## Design principles

- Writing4j has no browser, search engine or Playwright dependency, not even transitively.
- Hosts plug in their own sources, passages, search, embeddings, reranking, model calls and persistence through
  small interfaces; default implementations are optional.
- Source references may point to web pages, internal document IDs or code positions.
- When information is missing, Writing4j reports what it needs and the host decides where to look. Internal content
  is never sent to external search engines automatically.
- Gathering information is the job of [Research4j](https://github.com/aresstack/research4j).

More documentation follows with the first modules.
