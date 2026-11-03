/*
 * Copyright 2026 FIX Protocol Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 *
 */
package io.fixprotocol.orchestra.docgen;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;

import org.purl.dc.elements._1.SimpleLiteral;

import io.fixprotocol._2026.orchestra.repository.Annotation;
import io.fixprotocol._2026.orchestra.repository.Categories;
import io.fixprotocol._2026.orchestra.repository.CategoryType;
import io.fixprotocol._2026.orchestra.repository.CodeSetType;
import io.fixprotocol._2026.orchestra.repository.CodeType;
import io.fixprotocol._2026.orchestra.repository.ComponentRefType;
import io.fixprotocol._2026.orchestra.repository.ComponentType;
import io.fixprotocol._2026.orchestra.repository.Datatype;
import io.fixprotocol._2026.orchestra.repository.Documentation;
import io.fixprotocol._2026.orchestra.repository.FieldRefType;
import io.fixprotocol._2026.orchestra.repository.FieldRuleType;
import io.fixprotocol._2026.orchestra.repository.FieldType;
import io.fixprotocol._2026.orchestra.repository.GroupRefType;
import io.fixprotocol._2026.orchestra.repository.GroupType;
import io.fixprotocol._2026.orchestra.repository.MessageRefType;
import io.fixprotocol._2026.orchestra.repository.MessageType;
import io.fixprotocol._2026.orchestra.repository.PresenceT;
import io.fixprotocol._2026.orchestra.repository.Repository;
import io.fixprotocol._2026.orchestra.repository.ResponseType;
import io.fixprotocol._2026.orchestra.repository.SupportType;

/**
 * Generates a <a href="https://buildwithfern.com">Fern</a> docs reference from an Orchestra file.
 * <p>
 * Output is a folder of MDX pages plus a navigation file (<code>fix-reference.yml</code>) that can
 * be mounted as a product or version in a Fern <code>docs.yml</code>:
 *
 * <pre>
 * products:
 *   - display-name: FIX API
 *     path: ./fix/fix-reference.yml
 *     slug: fix
 * </pre>
 *
 * Pass the same slug as <code>--base-path /fix</code> so cross-page links resolve.
 */
public class FernDocGenerator {

  private static final String NAV_FILE = "fix-reference.yml";
  private static final String SOH = "|";
  private static final int FIELD_PAGE_SPAN = 200;
  private static final int MAX_USED_IN = 25;

  public static void main(final String[] args) throws Exception {
    String input = null;
    String output = "./fern-fix";
    String basePath = "";
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--base-path":
          basePath = args[++i];
          break;
        case "--output":
        case "-o":
          output = args[++i];
          break;
        default:
          if (input == null) {
            input = args[i];
          } else {
            output = args[i];
          }
      }
    }
    if (input == null) {
      System.err.println(
          "Usage: java -cp orchestra2doc-jar-with-dependencies.jar io.fixprotocol.orchestra.docgen.FernDocGenerator <input-file> [output-dir] [--base-path /fix]");
      System.exit(1);
    }
    try (InputStream in = new FileInputStream(input)) {
      new FernDocGenerator(in, Paths.get(output), basePath).generate();
    }
  }

  private final InputStream inputStream;
  private final Path outputDir;
  private final String basePath;

  private Repository repository;
  private final Map<Integer, FieldType> fieldsById = new HashMap<>();
  private final Map<Integer, ComponentType> componentsById = new HashMap<>();
  private final Map<Integer, GroupType> groupsById = new HashMap<>();
  private final Map<String, CodeSetType> codeSetsByName = new HashMap<>();
  private final Map<String, Datatype> datatypesByName = new LinkedHashMap<>();
  private final Map<String, CategoryType> categoriesByName = new LinkedHashMap<>();
  private final Map<Object, String> slugs = new HashMap<>();
  // usages: field id / component id / group id -> list of [title, href]
  private final Map<String, Set<String[]>> usedIn = new HashMap<>();

  public FernDocGenerator(final InputStream inputStream, final Path outputDir,
      final String basePath) {
    this.inputStream = inputStream;
    this.outputDir = outputDir;
    this.basePath = basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
  }

  public void generate() throws Exception {
    repository = unmarshal(inputStream);
    index();

    final Path pages = outputDir.resolve("pages");
    Files.createDirectories(pages.resolve("messages"));
    Files.createDirectories(pages.resolve("components"));
    Files.createDirectories(pages.resolve("groups"));
    Files.createDirectories(pages.resolve("fields"));

    final StringBuilder nav = new StringBuilder();
    nav.append("# Generated by orchestra2doc FernDocGenerator from Orchestra repository ")
        .append(repository.getName()).append(' ').append(repository.getVersion()).append('\n');
    nav.append("navigation:\n");

    write(pages.resolve("index.mdx"), overviewPage());
    nav.append("  - page: Overview\n    path: ./pages/index.mdx\n    slug: overview\n");

    generateMessages(pages.resolve("messages"), nav);
    generateComponents(pages.resolve("components"), nav);
    generateGroups(pages.resolve("groups"), nav);
    generateFields(pages.resolve("fields"), nav);

    write(pages.resolve("datatypes.mdx"), datatypesPage());
    nav.append("  - page: Datatypes\n    path: ./pages/datatypes.mdx\n    slug: datatypes\n");

    write(outputDir.resolve(NAV_FILE), nav.toString());
  }

  // ---------------------------------------------------------------- indexing

  private void index() {
    repository.getFields().getField().forEach(f -> fieldsById.put(f.getId(), f));
    repository.getComponents().getComponent().forEach(c -> componentsById.put(c.getId(), c));
    repository.getGroups().getGroup().forEach(g -> groupsById.put(g.getId(), g));
    repository.getCodeSets().getCodeSet().forEach(cs -> codeSetsByName.putIfAbsent(cs.getName(), cs));
    repository.getDatatypes().getDatatype().forEach(d -> datatypesByName.put(d.getName(), d));
    Optional.ofNullable(repository.getCategories()).orElse(new Categories()).getCategory()
        .forEach(c -> categoriesByName.put(c.getName(), c));

    assignSlugs(repository.getMessages().getMessage(), MessageType::getName, MessageType::getScenario);
    assignSlugs(repository.getComponents().getComponent(), ComponentType::getName, ComponentType::getScenario);
    assignSlugs(repository.getGroups().getGroup(), GroupType::getName, GroupType::getScenario);

    for (final MessageType m : repository.getMessages().getMessage()) {
      recordUsage(m.getStructure().getComponentRefOrGroupRefOrFieldRef(), title(m.getName(), m.getScenario()),
          messageHref(m));
    }
    for (final ComponentType c : repository.getComponents().getComponent()) {
      recordUsage(c.getComponentRefOrGroupRefOrFieldRef(), title(c.getName(), c.getScenario()), componentHref(c));
    }
    for (final GroupType g : repository.getGroups().getGroup()) {
      recordUsage(g.getComponentRefOrGroupRefOrFieldRef(), title(g.getName(), g.getScenario()), groupHref(g));
      if (g.getNumInGroup() != null) {
        addUsage("field:" + g.getNumInGroup().getId(), title(g.getName(), g.getScenario()), groupHref(g));
      }
    }
  }

  private <T> void assignSlugs(final List<T> items, final Function<T, String> name,
      final Function<T, String> scenario) {
    final Set<String> taken = new LinkedHashSet<>();
    for (final T item : items) {
      String slug = kebab(name.apply(item));
      final String sc = scenario.apply(item);
      if (sc != null && !"base".equals(sc)) {
        slug = slug + "-" + kebab(sc);
      }
      String unique = slug;
      for (int i = 2; taken.contains(unique); i++) {
        unique = slug + "-" + i;
      }
      taken.add(unique);
      slugs.put(item, unique);
    }
  }

  private void recordUsage(final List<Object> members, final String title, final String href) {
    for (final Object member : members) {
      if (member instanceof FieldRefType) {
        addUsage("field:" + ((FieldRefType) member).getId(), title, href);
      } else if (member instanceof GroupRefType) {
        addUsage("group:" + ((GroupRefType) member).getId(), title, href);
      } else if (member instanceof ComponentRefType) {
        addUsage("component:" + ((ComponentRefType) member).getId(), title, href);
      }
    }
  }

  private void addUsage(final String key, final String title, final String href) {
    final Set<String[]> set = usedIn.computeIfAbsent(key,
        k -> new java.util.TreeSet<>(Comparator.comparing((String[] a) -> a[0]).thenComparing(a -> a[1])));
    set.add(new String[] {title, href});
  }

  // ---------------------------------------------------------------- pages

  private String overviewPage() {
    final StringBuilder sb = new StringBuilder();
    final String title = metadata("title").filter(t -> !"Orchestra".equals(t))
        .orElse(Optional.ofNullable(repository.getName()).orElse("FIX") + " reference");
    frontmatter(sb, title, "Generated from an Orchestra file: " + repository.getName() + " "
        + repository.getVersion());

    sb.append("This reference is generated from a machine-readable [FIX Orchestra]")
        .append("(https://www.fixtrading.org/standards/fix-orchestra/) file, so it stays in sync with the spec.\n\n");

    sb.append("| | |\n|---|---|\n");
    sb.append("| Repository | `").append(cell(repository.getName())).append("` |\n");
    sb.append("| Version | `").append(cell(repository.getVersion())).append("` |\n");
    for (final String key : new String[] {"publisher", "date", "source", "rights"}) {
      metadata(key).ifPresent(v -> sb.append("| ").append(capitalize(key)).append(" | ").append(cell(v)).append(" |\n"));
    }
    sb.append('\n');

    sb.append("<CardGroup cols={2}>\n");
    card(sb, "Messages", "envelope", basePath + "/messages",
        repository.getMessages().getMessage().size() + " messages, grouped by category");
    card(sb, "Components", "cubes", basePath + "/components",
        repository.getComponents().getComponent().size() + " reusable blocks of fields");
    card(sb, "Repeating groups", "layer-group", basePath + "/groups",
        repository.getGroups().getGroup().size() + " repeating groups");
    card(sb, "Fields", "hashtag", basePath + "/fields",
        repository.getFields().getField().size() + " fields with tags and code sets");
    sb.append("</CardGroup>\n\n");

    sb.append("## Message syntax\n\n");
    sb.append("FIX tag=value messages are a sequence of `tag=value` pairs separated by the SOH (`0x01`) character. ")
        .append("Examples in this reference print SOH as `|`. Every message starts with the [standard header](")
        .append(componentHrefById(1024)).append(") and ends with the [standard trailer](")
        .append(componentHrefById(1025)).append(").\n");
    return sb.toString();
  }

  private void generateMessages(final Path dir, final StringBuilder nav) throws IOException {
    final List<MessageType> messages = repository.getMessages().getMessage().stream()
        .sorted(Comparator.comparing(MessageType::getName)).collect(Collectors.toList());

    final StringBuilder index = new StringBuilder();
    frontmatter(index, "Messages", "All messages defined in this Orchestra file");
    index.append("<StickySearchableTable placeholder=\"Search messages by name, MsgType, or category\">\n\n");
    index.append("| MsgType | Message | Category | Description |\n|---|---|---|---|\n");
    for (final MessageType m : messages) {
      index.append("| `").append(cell(m.getMsgType())).append("` | [").append(cell(title(m.getName(), m.getScenario())))
          .append("](").append(messageHref(m)).append(") | ").append(cell(nz(m.getCategory()))).append(" | ")
          .append(cell(synopsis(m.getAnnotation()))).append(" |\n");
    }
    index.append("\n</StickySearchableTable>\n");
    write(dir.resolve("index.mdx"), index.toString());

    nav.append("  - section: Messages\n    slug: messages\n    path: ./pages/messages/index.mdx\n    contents:\n");

    // group categories by their FIX section (Session, PreTrade, Trade, PostTrade, Infrastructure)
    final Map<String, List<CategoryType>> bySection = new TreeMap<>(Comparator.comparing(FernDocGenerator::sectionOrder)
        .thenComparing(Comparator.naturalOrder()));
    final Set<String> usedCategories = messages.stream().map(MessageType::getCategory)
        .filter(c -> c != null).collect(Collectors.toSet());
    for (final CategoryType c : categoriesByName.values()) {
      if (usedCategories.contains(c.getName())) {
        bySection.computeIfAbsent(nz(c.getSection()).isEmpty() ? "Other" : c.getSection(), k -> new ArrayList<>()).add(c);
      }
    }
    final Set<String> knownCategories = categoriesByName.keySet();
    final List<MessageType> uncategorized = messages.stream()
        .filter(m -> m.getCategory() == null || !knownCategories.contains(m.getCategory())).collect(Collectors.toList());

    for (final Map.Entry<String, List<CategoryType>> section : bySection.entrySet()) {
      nav.append("      - section: ").append(yaml(splitCamel(section.getKey()))).append('\n')
          .append("        skip-slug: true\n        collapsed: true\n        contents:\n");
      // a section holding one category of the same name (e.g. Session) is flattened
      final boolean flatten = section.getValue().size() == 1;
      final String indent = flatten ? "          " : "              ";
      for (final CategoryType category : section.getValue()) {
        if (!flatten) {
          nav.append("          - section: ").append(yaml(splitCamel(category.getName()))).append('\n')
              .append("            skip-slug: true\n            collapsed: true\n            contents:\n");
        }
        for (final MessageType m : messages) {
          if (category.getName().equals(m.getCategory())) {
            nav.append(indent).append("- page: ").append(yaml(title(m.getName(), m.getScenario()))).append('\n')
                .append(indent).append("  path: ./pages/messages/").append(slugs.get(m)).append(".mdx\n")
                .append(indent).append("  slug: ").append(slugs.get(m)).append('\n');
          }
        }
      }
    }
    if (!uncategorized.isEmpty()) {
      nav.append("      - section: Other\n        skip-slug: true\n        collapsed: true\n        contents:\n");
      for (final MessageType m : uncategorized) {
        nav.append("          - page: ").append(yaml(title(m.getName(), m.getScenario()))).append('\n')
            .append("            path: ./pages/messages/").append(slugs.get(m)).append(".mdx\n")
            .append("            slug: ").append(slugs.get(m)).append('\n');
      }
    }

    for (final MessageType m : messages) {
      write(dir.resolve(slugs.get(m) + ".mdx"), messagePage(m));
    }
  }

  private String messagePage(final MessageType m) {
    final StringBuilder sb = new StringBuilder();
    frontmatter(sb, title(m.getName(), m.getScenario()), synopsis(m.getAnnotation()));

    sb.append("<Badge intent=\"info\">MsgType ").append(escapeText(m.getMsgType())).append("</Badge>");
    if (m.getCategory() != null) {
      sb.append(" <Badge intent=\"note\" minimal>").append(escapeText(splitCamel(m.getCategory()))).append("</Badge>");
    }
    final CategoryType cat = m.getCategory() == null ? null : categoriesByName.get(m.getCategory());
    if (cat != null && cat.getSection() != null) {
      sb.append(" <Badge intent=\"tip\" minimal>").append(escapeText(splitCamel(cat.getSection()))).append("</Badge>");
    }
    lifecycleBadges(sb, m.getAdded(), m.getDeprecated());
    sb.append("\n\n");

    elaboration(sb, m.getAnnotation());

    if (m.getResponses() != null && !m.getResponses().getResponse().isEmpty()) {
      sb.append("## Responses\n\n| Response | When |\n|---|---|\n");
      for (final ResponseType r : m.getResponses().getResponse()) {
        for (final Object ref : r.getMessageRefOrAssignOrTrigger()) {
          if (ref instanceof MessageRefType) {
            final MessageRefType mr = (MessageRefType) ref;
            final String href = repository.getMessages().getMessage().stream()
                .filter(x -> x.getName().equals(mr.getName())
                    && nz(x.getScenario()).equals(nz(mr.getScenario()).isEmpty() ? "base" : mr.getScenario()))
                .findFirst().map(this::messageHref).orElse(null);
            sb.append("| ").append(href != null ? "[" + cell(mr.getName()) + "](" + href + ")" : cell(mr.getName()))
                .append(" | ").append(r.getWhen() != null ? "`" + cell(r.getWhen()) + "`" : "").append(" |\n");
          }
        }
      }
      sb.append('\n');
    }

    sb.append("## Message structure\n\n");
    membersTable(sb, m.getStructure().getComponentRefOrGroupRefOrFieldRef());

    sb.append("## Example\n\n");
    sb.append("Required fields only, with illustrative values. `|` represents the SOH delimiter.\n\n");
    sb.append("```text title=\"").append(escapeAttr(m.getName())).append("\" wordWrap\n")
        .append(exampleMessage(m)).append("\n```\n");
    return sb.toString();
  }

  private void generateComponents(final Path dir, final StringBuilder nav) throws IOException {
    final List<ComponentType> components = repository.getComponents().getComponent().stream()
        .sorted(Comparator.comparing(ComponentType::getName)).collect(Collectors.toList());
    final StringBuilder index = new StringBuilder();
    frontmatter(index, "Components", "Reusable blocks of fields shared across messages");
    index.append("<StickySearchableTable placeholder=\"Search components\">\n\n| Component | Category | Description |\n|---|---|---|\n");
    nav.append("  - section: Components\n    slug: components\n    path: ./pages/components/index.mdx\n    collapsed: true\n    contents:\n");
    for (final ComponentType c : components) {
      index.append("| [").append(cell(title(c.getName(), c.getScenario()))).append("](").append(componentHref(c))
          .append(") | ").append(cell(splitCamel(nz(c.getCategory())))).append(" | ")
          .append(cell(synopsis(c.getAnnotation()))).append(" |\n");
      nav.append("      - page: ").append(yaml(title(c.getName(), c.getScenario()))).append('\n')
          .append("        path: ./pages/components/").append(slugs.get(c)).append(".mdx\n")
          .append("        slug: ").append(slugs.get(c)).append('\n');

      final StringBuilder sb = new StringBuilder();
      frontmatter(sb, title(c.getName(), c.getScenario()), synopsis(c.getAnnotation()));
      sb.append("<Badge intent=\"note\">Component</Badge>");
      if (c.getCategory() != null) {
        sb.append(" <Badge intent=\"note\" minimal>").append(escapeText(splitCamel(c.getCategory()))).append("</Badge>");
      }
      lifecycleBadges(sb, c.getAdded(), c.getDeprecated());
      sb.append("\n\n");
      elaboration(sb, c.getAnnotation());
      sb.append("## Members\n\n");
      membersTable(sb, c.getComponentRefOrGroupRefOrFieldRef());
      usedInSection(sb, "component:" + c.getId());
      write(dir.resolve(slugs.get(c) + ".mdx"), sb.toString());
    }
    index.append("\n</StickySearchableTable>\n");
    write(dir.resolve("index.mdx"), index.toString());
  }

  private void generateGroups(final Path dir, final StringBuilder nav) throws IOException {
    final List<GroupType> groups = repository.getGroups().getGroup().stream()
        .sorted(Comparator.comparing(GroupType::getName)).collect(Collectors.toList());
    final StringBuilder index = new StringBuilder();
    frontmatter(index, "Repeating groups", "Repeating groups and their NumInGroup counter fields");
    index.append("<StickySearchableTable placeholder=\"Search repeating groups\">\n\n| Group | NumInGroup | Description |\n|---|---|---|\n");
    nav.append("  - section: Repeating groups\n    slug: groups\n    path: ./pages/groups/index.mdx\n    collapsed: true\n    contents:\n");
    for (final GroupType g : groups) {
      final FieldType counter = g.getNumInGroup() == null ? null : fieldsById.get(g.getNumInGroup().getId());
      index.append("| [").append(cell(title(g.getName(), g.getScenario()))).append("](").append(groupHref(g))
          .append(") | ").append(counter == null ? "" : "[" + counter.getId() + " " + cell(counter.getName()) + "](" + fieldHref(counter) + ")")
          .append(" | ").append(cell(synopsis(g.getAnnotation()))).append(" |\n");
      nav.append("      - page: ").append(yaml(title(g.getName(), g.getScenario()))).append('\n')
          .append("        path: ./pages/groups/").append(slugs.get(g)).append(".mdx\n")
          .append("        slug: ").append(slugs.get(g)).append('\n');

      final StringBuilder sb = new StringBuilder();
      frontmatter(sb, title(g.getName(), g.getScenario()), synopsis(g.getAnnotation()));
      sb.append("<Badge intent=\"note\">Repeating group</Badge>");
      if (counter != null) {
        sb.append(" <Badge intent=\"info\" minimal>NumInGroup ").append(counter.getId()).append("</Badge>");
      }
      lifecycleBadges(sb, g.getAdded(), g.getDeprecated());
      sb.append("\n\n");
      elaboration(sb, g.getAnnotation());
      if (counter != null) {
        sb.append("Each repetition starts with the first member listed. The count of repetitions is sent in [")
            .append(counter.getName()).append(" (").append(counter.getId()).append(")](").append(fieldHref(counter))
            .append(").\n\n");
      }
      sb.append("## Members\n\n");
      membersTable(sb, g.getComponentRefOrGroupRefOrFieldRef());
      usedInSection(sb, "group:" + g.getId());
      write(dir.resolve(slugs.get(g) + ".mdx"), sb.toString());
    }
    index.append("\n</StickySearchableTable>\n");
    write(dir.resolve("index.mdx"), index.toString());
  }

  private void generateFields(final Path dir, final StringBuilder nav) throws IOException {
    final Map<Integer, List<FieldType>> buckets = new TreeMap<>();
    for (final FieldType f : repository.getFields().getField()) {
      buckets.computeIfAbsent(f.getId() / FIELD_PAGE_SPAN, k -> new ArrayList<>()).add(f);
    }

    final StringBuilder index = new StringBuilder();
    frontmatter(index, "Fields", "Every field by tag number");
    index.append("<StickySearchableTable placeholder=\"Search by tag number or field name\">\n\n| Tag | Field | Type | Description |\n|---|---|---|---|\n");
    repository.getFields().getField().stream().sorted(Comparator.comparingInt(FieldType::getId)).forEach(f ->
        index.append("| ").append(f.getId()).append(" | [").append(cell(f.getName())).append("](")
            .append(fieldHref(f)).append(") | ").append(cell(f.getType())).append(" | ")
            .append(cell(truncate(synopsis(f.getAnnotation()), 160))).append(" |\n"));
    index.append("\n</StickySearchableTable>\n");
    write(dir.resolve("index.mdx"), index.toString());

    nav.append("  - section: Fields\n    slug: fields\n    path: ./pages/fields/index.mdx\n    collapsed: true\n    contents:\n");
    for (final Map.Entry<Integer, List<FieldType>> bucket : buckets.entrySet()) {
      final String slug = fieldPageSlug(bucket.getKey());
      final List<FieldType> fields = bucket.getValue();
      fields.sort(Comparator.comparingInt(FieldType::getId));
      final String pageTitle = "Tags " + fields.get(0).getId() + "–" + fields.get(fields.size() - 1).getId();
      nav.append("      - page: ").append(yaml(pageTitle)).append('\n')
          .append("        path: ./pages/fields/").append(slug).append(".mdx\n")
          .append("        slug: ").append(slug).append('\n');

      final StringBuilder sb = new StringBuilder();
      frontmatter(sb, pageTitle, fields.size() + " fields");
      for (final FieldType f : fields) {
        sb.append("## Tag ").append(f.getId()).append(": ").append(escapeText(f.getName())).append("\n\n");
        sb.append("<Badge intent=\"info\" minimal>Tag ").append(f.getId()).append("</Badge> <Badge intent=\"note\" minimal>")
            .append(escapeText(f.getType())).append("</Badge>");
        lifecycleBadges(sb, f.getAdded(), f.getDeprecated());
        sb.append("\n\n");
        final String syn = synopsis(f.getAnnotation());
        if (!syn.isEmpty()) {
          sb.append(escapeText(syn)).append("\n\n");
        }
        elaboration(sb, f.getAnnotation());

        final CodeSetType codeSet = codeSetsByName.get(f.getType());
        if (codeSet != null) {
          sb.append("Code set **").append(escapeText(codeSet.getName())).append("** (")
              .append(typeLink(codeSet.getType())).append(")\n\n");
          sb.append("| Value | Name | Description |\n|---|---|---|\n");
          for (final CodeType code : codeSet.getCode()) {
            sb.append("| `").append(cell(code.getValue())).append("` | ").append(cell(code.getName())).append(" | ")
                .append(cell(synopsis(code.getAnnotation())))
                .append(code.getSupported() == SupportType.FORBIDDEN ? " *(not supported)*" : "").append(" |\n");
          }
          sb.append('\n');
        } else {
          sb.append("Type: ").append(typeLink(f.getType())).append("\n\n");
        }
        final Set<String[]> uses = usedIn.get("field:" + f.getId());
        if (uses != null && !uses.isEmpty()) {
          sb.append("Used in: ").append(usedInList(uses)).append("\n\n");
        }
      }
      write(dir.resolve(slug + ".mdx"), sb.toString());
    }
  }

  private String datatypesPage() {
    final StringBuilder sb = new StringBuilder();
    frontmatter(sb, "Datatypes", "Datatypes used by FIX fields");
    for (final Datatype d : datatypesByName.values()) {
      sb.append("## ").append(escapeText(d.getName())).append("\n\n");
      if (d.getBaseType() != null) {
        sb.append("Base type: ").append(typeLink(d.getBaseType())).append("\n\n");
      }
      final String syn = synopsis(d.getAnnotation());
      if (!syn.isEmpty()) {
        sb.append(escapeText(syn)).append("\n\n");
      }
      elaboration(sb, d.getAnnotation());
    }
    return sb.toString();
  }

  // ---------------------------------------------------------------- building blocks

  private void membersTable(final StringBuilder sb, final List<Object> members) {
    if (members.isEmpty()) {
      sb.append("This block has no members.\n\n");
      return;
    }
    sb.append("<StickyTable>\n\n| Tag | Name | Type | Presence | Description |\n|---|---|---|---|---|\n");
    for (final Object member : members) {
      if (member instanceof FieldRefType) {
        final FieldRefType ref = (FieldRefType) member;
        final FieldType f = fieldsById.get(ref.getId());
        if (f == null) {
          continue;
        }
        String desc = documentation(ref.getAnnotation());
        if (desc.isEmpty()) {
          desc = synopsis(f.getAnnotation());
        }
        sb.append("| ").append(f.getId()).append(" | [").append(cell(f.getName())).append("](").append(fieldHref(f))
            .append(") | ").append(cell(f.getType())).append(" | ").append(presence(ref)).append(" | ")
            .append(cell(truncate(desc, 160))).append(" |\n");
      } else if (member instanceof GroupRefType) {
        final GroupRefType ref = (GroupRefType) member;
        final GroupType g = groupsById.get(ref.getId());
        if (g == null) {
          continue;
        }
        final FieldType counter = g.getNumInGroup() == null ? null : fieldsById.get(g.getNumInGroup().getId());
        sb.append("| ").append(counter == null ? "" : String.valueOf(counter.getId())).append(" | [")
            .append(cell(g.getName())).append("](").append(groupHref(g)).append(") | Repeating group | ")
            .append(componentPresence(ref)).append(" | ").append(cell(truncate(synopsis(g.getAnnotation()), 160)))
            .append(" |\n");
      } else if (member instanceof ComponentRefType) {
        final ComponentRefType ref = (ComponentRefType) member;
        final ComponentType c = componentsById.get(ref.getId());
        if (c == null) {
          continue;
        }
        sb.append("| | [").append(cell(c.getName())).append("](").append(componentHref(c)).append(") | Component | ")
            .append(componentPresence(ref)).append(" | ").append(cell(truncate(synopsis(c.getAnnotation()), 160)))
            .append(" |\n");
      }
    }
    sb.append("\n</StickyTable>\n\n");
  }

  private String presence(final FieldRefType ref) {
    if (ref.getSupported() == SupportType.FORBIDDEN) {
      return "Not supported";
    }
    switch (ref.getPresence()) {
      case CONSTANT:
        return "Constant `" + cell(ref.getValue()) + "`";
      case FORBIDDEN:
        return "Forbidden";
      case IGNORED:
        return "Ignored";
      case REQUIRED:
        return "**Required**";
      case OPTIONAL:
      default:
        for (final FieldRuleType rule : ref.getRule()) {
          if (rule.getPresence() == PresenceT.REQUIRED) {
            return "Required when `" + cell(rule.getWhen()) + "`";
          }
        }
        return "Optional";
    }
  }

  private String componentPresence(final ComponentRefType ref) {
    if (ref.getSupported() == SupportType.FORBIDDEN) {
      return "Not supported";
    }
    return ref.getPresence() == PresenceT.REQUIRED ? "**Required**" : capitalize(ref.getPresence().value());
  }

  private String exampleMessage(final MessageType m) {
    final List<String[]> body = new ArrayList<>();
    final List<String[]> header = new ArrayList<>();
    final List<String[]> trailer = new ArrayList<>();
    for (final Object member : m.getStructure().getComponentRefOrGroupRefOrFieldRef()) {
      if (member instanceof ComponentRefType && !(member instanceof GroupRefType)
          && ((ComponentRefType) member).getId() == 1024) {
        collectRequired(componentsById.get(1024), header, 0);
      } else if (member instanceof ComponentRefType && !(member instanceof GroupRefType)
          && ((ComponentRefType) member).getId() == 1025) {
        collectRequired(componentsById.get(1025), trailer, 0);
      } else {
        collectRequired(Collections.singletonList(member), body, 0);
      }
    }
    final List<String[]> pairs = new ArrayList<>();
    for (final String[] kv : header) {
      if (kv[0].equals("35")) {
        kv[1] = m.getMsgType();
      }
      pairs.add(kv);
    }
    if (header.isEmpty()) {
      pairs.add(new String[] {"8", "FIXT.1.1"});
      pairs.add(new String[] {"9", "0"});
      pairs.add(new String[] {"35", m.getMsgType()});
    }
    pairs.addAll(body);

    // compute BodyLength (9) and CheckSum (10) over the SOH-delimited message
    final StringBuilder afterLength = new StringBuilder();
    boolean pastLength = false;
    for (final String[] kv : pairs) {
      if (pastLength) {
        afterLength.append(kv[0]).append('=').append(kv[1]).append('\u0001');
      }
      if (kv[0].equals("9")) {
        pastLength = true;
      }
    }
    for (final String[] kv : trailer) {
      if (!kv[0].equals("10")) {
        afterLength.append(kv[0]).append('=').append(kv[1]).append('\u0001');
      }
    }
    final int bodyLength = afterLength.toString().getBytes(StandardCharsets.US_ASCII).length;
    final StringBuilder wire = new StringBuilder();
    for (final String[] kv : pairs) {
      if (kv[0].equals("9")) {
        kv[1] = String.valueOf(bodyLength);
      }
      wire.append(kv[0]).append('=').append(kv[1]).append('\u0001');
      if (kv[0].equals("9")) {
        break;
      }
    }
    wire.append(afterLength);
    int sum = 0;
    for (final byte b : wire.toString().getBytes(StandardCharsets.US_ASCII)) {
      sum += b & 0xff;
    }
    wire.append("10=").append(String.format("%03d", sum % 256)).append('\u0001');
    return wire.toString().replace("\u0001", SOH);
  }

  private void collectRequired(final ComponentType c, final List<String[]> out, final int depth) {
    if (c != null) {
      collectRequired(c.getComponentRefOrGroupRefOrFieldRef(), out, depth);
    }
  }

  private void collectRequired(final List<Object> members, final List<String[]> out, final int depth) {
    if (depth > 6) {
      return;
    }
    for (final Object member : members) {
      if (member instanceof FieldRefType) {
        final FieldRefType ref = (FieldRefType) member;
        if (ref.getPresence() == PresenceT.REQUIRED || ref.getPresence() == PresenceT.CONSTANT) {
          final FieldType f = fieldsById.get(ref.getId());
          if (f != null) {
            out.add(new String[] {String.valueOf(f.getId()),
                ref.getPresence() == PresenceT.CONSTANT && ref.getValue() != null ? ref.getValue() : sampleValue(f)});
          }
        }
      } else if (member instanceof GroupRefType) {
        final GroupRefType ref = (GroupRefType) member;
        if (ref.getPresence() == PresenceT.REQUIRED) {
          final GroupType g = groupsById.get(ref.getId());
          if (g != null && g.getNumInGroup() != null) {
            out.add(new String[] {String.valueOf(g.getNumInGroup().getId()), "1"});
            collectRequiredGroupEntry(g, out, depth + 1);
          }
        }
      } else if (member instanceof ComponentRefType) {
        final ComponentRefType ref = (ComponentRefType) member;
        if (ref.getPresence() == PresenceT.REQUIRED) {
          collectRequired(componentsById.get(ref.getId()), out, depth + 1);
        }
      }
    }
  }

  private void collectRequiredGroupEntry(final GroupType g, final List<String[]> out, final int depth) {
    final List<Object> members = g.getComponentRefOrGroupRefOrFieldRef();
    final int before = out.size();
    collectRequired(members, out, depth);
    // a repetition must start with the group's first field (the delimiter)
    if (!members.isEmpty() && members.get(0) instanceof FieldRefType) {
      final FieldType first = fieldsById.get(((FieldRefType) members.get(0)).getId());
      final boolean present = out.subList(before, out.size()).stream()
          .anyMatch(kv -> first != null && kv[0].equals(String.valueOf(first.getId())));
      if (first != null && !present) {
        out.add(before, new String[] {String.valueOf(first.getId()), sampleValue(first)});
      }
    }
  }

  private String sampleValue(final FieldType f) {
    switch (f.getId()) {
      case 8:
        return "FIXT.1.1";
      case 49:
        return "BUYSIDE";
      case 56:
        return "SELLSIDE";
      case 34:
        return "42";
      case 52:
        return "20260102-14:30:00.000";
      default:
        break;
    }
    final CodeSetType cs = codeSetsByName.get(f.getType());
    if (cs != null && !cs.getCode().isEmpty()) {
      return cs.getCode().get(0).getValue();
    }
    final String type = nz(f.getType());
    switch (type) {
      case "int":
      case "Length":
      case "NumInGroup":
      case "SeqNum":
      case "TagNum":
        return "1";
      case "Qty":
        return "100";
      case "Price":
      case "PriceOffset":
      case "Amt":
        return "10.25";
      case "float":
      case "Percentage":
        return "0.5";
      case "char":
        return "A";
      case "Boolean":
        return "Y";
      case "UTCTimestamp":
        return "20260102-14:30:00.000";
      case "UTCTimeOnly":
      case "LocalMktTime":
        return "14:30:00";
      case "UTCDateOnly":
      case "LocalMktDate":
      case "date":
        return "20260102";
      case "MonthYear":
        return "202603";
      case "Currency":
        return "USD";
      case "Exchange":
        return "XNYS";
      case "Country":
        return "US";
      case "Language":
        return "en";
      case "data":
      case "XMLData":
        return "...";
      default:
        return "ABC123";
    }
  }

  private void usedInSection(final StringBuilder sb, final String key) {
    final Set<String[]> uses = usedIn.get(key);
    if (uses != null && !uses.isEmpty()) {
      sb.append("## Used in\n\n").append(usedInList(uses)).append("\n");
    }
  }

  private String usedInList(final Collection<String[]> uses) {
    final List<String> links = uses.stream().limit(MAX_USED_IN)
        .map(u -> "[" + escapeText(u[0]) + "](" + u[1] + ")").collect(Collectors.toList());
    String result = String.join(", ", links);
    if (uses.size() > MAX_USED_IN) {
      result += ", and " + (uses.size() - MAX_USED_IN) + " more";
    }
    return result;
  }

  private void elaboration(final StringBuilder sb, final Annotation annotation) {
    if (annotation == null) {
      return;
    }
    for (final Object o : annotation.getDocumentationOrAppinfo()) {
      if (o instanceof Documentation) {
        final Documentation d = (Documentation) o;
        final String purpose = nz(d.getPurpose());
        if (!purpose.equals("SYNOPSIS") && d.getContent() != null && !d.getContent().isBlank()) {
          sb.append(escapeProse(d.getContent())).append("\n\n");
        }
      }
    }
  }

  private void lifecycleBadges(final StringBuilder sb, final String added, final String deprecated) {
    if (added != null) {
      sb.append(" <Badge intent=\"success\" minimal>Added ").append(escapeText(added)).append("</Badge>");
    }
    if (deprecated != null) {
      sb.append(" <Badge intent=\"warning\">Deprecated ").append(escapeText(deprecated)).append("</Badge>");
    }
  }

  private void card(final StringBuilder sb, final String title, final String icon, final String href,
      final String body) {
    sb.append("  <Card title=\"").append(escapeAttr(title)).append("\" icon=\"").append(icon).append("\" href=\"")
        .append(href).append("\">\n    ").append(escapeText(body)).append("\n  </Card>\n");
  }

  private static void frontmatter(final StringBuilder sb, final String title, final String subtitle) {
    sb.append("---\ntitle: ").append(yaml(title)).append('\n');
    if (subtitle != null && !subtitle.isEmpty()) {
      sb.append("subtitle: ").append(yaml(truncate(subtitle, 240))).append('\n');
    }
    sb.append("---\n\n");
  }

  // ---------------------------------------------------------------- links

  private String messageHref(final MessageType m) {
    return basePath + "/messages/" + slugs.get(m);
  }

  private String componentHref(final ComponentType c) {
    return basePath + "/components/" + slugs.get(c);
  }

  private String componentHrefById(final int id) {
    final ComponentType c = componentsById.get(id);
    return c == null ? basePath + "/components" : componentHref(c);
  }

  private String groupHref(final GroupType g) {
    return basePath + "/groups/" + slugs.get(g);
  }

  private String fieldHref(final FieldType f) {
    return basePath + "/fields/" + fieldPageSlug(f.getId() / FIELD_PAGE_SPAN) + "#tag-" + f.getId() + "-" + f.getName().toLowerCase();
  }

  private static String fieldPageSlug(final int bucket) {
    return "tags-" + (bucket * FIELD_PAGE_SPAN) + "-" + (bucket * FIELD_PAGE_SPAN + FIELD_PAGE_SPAN - 1);
  }

  private String typeLink(final String type) {
    if (type == null) {
      return "";
    }
    if (datatypesByName.containsKey(type)) {
      return "[" + escapeText(type) + "](" + basePath + "/datatypes#" + type.toLowerCase() + ")";
    }
    return "`" + escapeText(type) + "`";
  }

  // ---------------------------------------------------------------- text helpers

  private String synopsis(final Annotation annotation) {
    if (annotation == null) {
      return "";
    }
    for (final Object o : annotation.getDocumentationOrAppinfo()) {
      if (o instanceof Documentation) {
        final Documentation d = (Documentation) o;
        if ("SYNOPSIS".equals(d.getPurpose()) && d.getContent() != null) {
          return collapse(d.getContent());
        }
      }
    }
    return documentation(annotation);
  }

  private static String documentation(final Annotation annotation) {
    if (annotation == null) {
      return "";
    }
    for (final Object o : annotation.getDocumentationOrAppinfo()) {
      if (o instanceof Documentation && ((Documentation) o).getContent() != null) {
        final String s = collapse(((Documentation) o).getContent());
        if (!s.isEmpty()) {
          return s;
        }
      }
    }
    return "";
  }

  private Optional<String> metadata(final String localName) {
    if (repository.getMetadata() == null) {
      return Optional.empty();
    }
    for (final JAXBElement<SimpleLiteral> element : repository.getMetadata().getAny()) {
      if (element.getName().getLocalPart().equals(localName)) {
        final String v = String.join(" ", element.getValue().getContent()).trim();
        if (!v.isEmpty()) {
          return Optional.of(v);
        }
      }
    }
    return Optional.empty();
  }

  private static String title(final String name, final String scenario) {
    return scenario == null || "base".equals(scenario) ? name : name + " (" + scenario + ")";
  }

  private static int sectionOrder(final String section) {
    final List<String> order = List.of("Session", "PreTrade", "Trade", "PostTrade", "Infrastructure");
    final int i = order.indexOf(section);
    return i < 0 ? order.size() : i;
  }

  static String kebab(final String s) {
    return s.replaceAll("([a-z0-9])([A-Z])", "$1-$2").replaceAll("([A-Z]+)([A-Z][a-z])", "$1-$2")
        .replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-|-$)", "").toLowerCase();
  }

  private static String splitCamel(final String s) {
    return s.replaceAll("([a-z])([A-Z])", "$1 $2").replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2");
  }

  private static String capitalize(final String s) {
    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  private static String nz(final String s) {
    return s == null ? "" : s;
  }

  private static String collapse(final String s) {
    return s == null ? "" : s.replaceAll("\\s+", " ").trim();
  }

  private static String truncate(final String s, final int max) {
    if (s.length() <= max) {
      return s;
    }
    final int cut = s.lastIndexOf(' ', max);
    return s.substring(0, cut > max / 2 ? cut : max) + "…";
  }

  /** Escapes text so MDX treats it as literal prose. */
  static String escapeText(final String s) {
    if (s == null) {
      return "";
    }
    return s.replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}").replace("<", "&lt;")
        .replace(">", "&gt;").replace("`", "\\`").replace("*", "\\*").replace("_", "\\_")
        .replace("[", "\\[").replace("]", "\\]");
  }

  private static String escapeProse(final String s) {
    final String[] paragraphs = s.trim().split("\\n\\s*\\n");
    return java.util.Arrays.stream(paragraphs).map(p -> escapeText(collapse(p)).replaceAll("^([#>+\\-]|\\d+\\.)", "\\\\$1"))
        .filter(p -> !p.isEmpty()).collect(Collectors.joining("\n\n"));
  }

  private static String cell(final String s) {
    return escapeText(collapse(s)).replace("|", "\\|");
  }

  private static String escapeAttr(final String s) {
    return nz(s).replace("\"", "&quot;");
  }

  private static String yaml(final String s) {
    return "\"" + collapse(s).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static void write(final Path path, final String content) throws IOException {
    Files.createDirectories(path.getParent());
    Files.write(path, llmsBadges(content).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Badges flatten to unlabeled lines in Fern's Markdown/llms.txt output, so each badge row is
   * shown to humans only and replaced by a single labeled line for agents.
   */
  static String llmsBadges(final String content) {
    final StringBuilder out = new StringBuilder();
    for (final String line : content.split("\n", -1)) {
      if (line.startsWith("<Badge")) {
        final String plain = line.replaceAll("<Badge[^>]*>", "").replaceAll("</Badge>\\s*", " · ")
            .replaceAll("\\s*·\\s*$", "").trim();
        out.append("<llms-ignore>\n").append(line).append("\n</llms-ignore>\n\n<llms-only>\n")
            .append(plain).append("\n</llms-only>\n");
      } else {
        out.append(line).append('\n');
      }
    }
    return out.substring(0, out.length() - 1);
  }

  private static Repository unmarshal(final InputStream inputStream) throws JAXBException {
    final JAXBContext jaxbContext = JAXBContext.newInstance(Repository.class);
    final Unmarshaller jaxbUnmarshaller = jaxbContext.createUnmarshaller();
    return (Repository) jaxbUnmarshaller.unmarshal(inputStream);
  }
}
