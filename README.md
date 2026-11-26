<p align="center"><img src="docs/banner.png" alt="Writing4j: evidence-based writing and document synthesis for Java" width="100%"></p>

# Writing4j

Evidence-based writing and document synthesis for Java. Writing4j turns collected sources and passages into a
structured, cited document: outline planning, evidence assignment, section drafting, revision, citation management,
quality checks and manuscript assembly.

> **Status:** in preparation. The library is being extracted from the writing phases of
> [Ask AI](https://github.com/aresstack/askai). Module layout and contracts are defined in an architecture decision
> that is under review; no code or release exists yet.

## Planned scope

- **Outline planning:** chapters and sections from the topics found in the sources, with section budgets.
- **Evidence packs:** the passages each section may cite, assigned before writing.
- **Draft generation:** section by section, through a model port supplied by the host.
- **Citation management:** every claim tied to a source reference, validated after writing.
- **Document quality checks:** claim support, repetition, contradictions, voice and topic drift.
- **Manuscript assembly:** sections, revisions and references into one document with a quality report.

## Design principles

- Writing4j has no browser, search engine or Playwright dependency, not even transitively.
- Hosts plug in their own sources, passages, search, embeddings, reranking, model calls and persistence through
  small interfaces; default implementations are optional.
- Source references may point to web pages, internal document IDs or code positions.
- When information is missing, Writing4j reports what it needs and the host decides where to look. Internal content
  is never sent to external search engines automatically.
- Gathering information is the job of [Research4j](https://github.com/aresstack/research4j).

More documentation follows with the first modules.
