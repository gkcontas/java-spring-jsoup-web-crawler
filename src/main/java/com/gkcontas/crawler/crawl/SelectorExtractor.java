package com.gkcontas.crawler.crawl;

import com.gkcontas.crawler.dto.SelectorDefinition;
import com.gkcontas.crawler.exception.InvalidSelectorException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.jsoup.select.Selector;
import org.springframework.stereotype.Component;

@Component
public class SelectorExtractor {

    public Map<String, Object> extract(Document document, Map<String, SelectorDefinition> selectors) {
        if (selectors == null || selectors.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        selectors.forEach((field, definition) -> data.put(field, valueFor(document, field, definition)));
        return data;
    }

    private static Object valueFor(Document document, String field, SelectorDefinition definition) {
        Elements matches = select(document, field, definition.css());
        if (definition.isMultiple()) {
            return matches.stream().map(element -> readValue(element, definition)).toList();
        }
        // A missing field comes back as null rather than being absent from the response,
        // so the caller can tell "the selector matched nothing" from "I misspelled the
        // field name".
        return matches.isEmpty() ? null : readValue(matches.first(), definition);
    }

    private static Elements select(Document document, String field, String css) {
        try {
            return document.select(css);
        } catch (Selector.SelectorParseException e) {
            // Bad CSS is the caller's mistake, so it has to surface as 400 rather than
            // as a 500 with a stack trace.
            throw new InvalidSelectorException(field, css, e.getMessage());
        }
    }

    private static String readValue(Element element, SelectorDefinition definition) {
        String attribute = definition.attribute();
        if (attribute == null || attribute.isBlank()) {
            return element.text();
        }
        // absUrl resolves href/src against <base> and the page URL, so a relative
        // "/img/a.png" comes back usable instead of leaving the caller to join it.
        String absolute = element.absUrl(attribute);
        return absolute.isEmpty() ? element.attr(attribute) : absolute;
    }

    static List<String> supportedValueKinds() {
        return List.of("text", "attribute");
    }
}
