package com.BookEngine.extractor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.BookEngine.dto.PdfTextBlock;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

@Component
public class PdfTextExtractor {

	private static final Pattern LIST_ITEM = Pattern.compile(
			"^(?:[•●▪◦‣⁃*-]|(?:\\d+|[A-Za-z])[.)])\\s+(.+)$");
	private static final Pattern STRUCTURAL_HEADING = Pattern.compile(
			"(?i)^(?:(?:part|chapter|book|volume|section|appendix)\\s+"
					+ "(?:[ivxlcdm]+|\\d+[a-z]?|[a-z]+)\\b|"
					+ "prologue|epilogue|introduction|conclusion|preface|foreword|"
					+ "dedication|acknowledgements?|contents|table of contents|bibliography|index)\\b.*$");
	private static final Pattern TOC_TITLE = Pattern.compile(
			"(?i)^(?:table of contents|contents|in this book|contents at a glance)$");
	private static final Pattern TOC_LEADER = Pattern.compile("\\s*(?:\\.{2,}|·{2,}|…{1,})\\s*");

	public PdfExtractionResult extract(byte[] pdfContent) {
		try (PDDocument document = Loader.loadPDF(pdfContent)) {
			PDDocumentInformation information = document.getDocumentInformation();
			PositionAwareTextStripper stripper = new PositionAwareTextStripper();
			stripper.setSortByPosition(true);
			stripper.getText(document);
			List<PdfTextBlock> blocks = structureText(stripper.pages());
			String text = blocks.stream().map(PdfTextBlock::text).reduce((left, right) -> left + "\n\n" + right)
					.orElse("");

			return new PdfExtractionResult(
					document.getNumberOfPages(),
					information.getTitle(),
					information.getAuthor(),
					information.getSubject(),
					information.getCreator(),
					information.getProducer(),
					text,
					blocks);
		}
		catch (InvalidPasswordException exception) {
			throw new PdfExtractionException(exception);
		}
		catch (IOException exception) {
			throw new PdfExtractionException(exception);
		}
	}

	private List<PdfTextBlock> structureText(List<List<PositionedLine>> pages) {
		List<PositionedLine> allLines = pages.stream().flatMap(List::stream).toList();
		if (allLines.isEmpty()) {
			return List.of();
		}

		double typicalFontSize = median(allLines.stream().mapToDouble(PositionedLine::fontSize).toArray());
		Map<String, Integer> repeatedPageLines = repeatedPageLines(pages);
		List<PdfTextBlock> blocks = new ArrayList<>();
		BlockBuilder builder = new BlockBuilder(blocks);
		boolean inContents = false;

		for (List<PositionedLine> page : pages) {
			List<PositionedLine> pageContent = page.stream()
					.filter(line -> !isRepeatedPageLine(line, repeatedPageLines))
					.toList();
			List<PositionedLine> visibleLines = orderForReading(
					combineTableRows(removeDuplicateLines(pageContent)));
			double leftMargin = visibleLines.stream().mapToDouble(PositionedLine::x).min().orElse(0);
			PositionedLine firstLine = visibleLines.stream().filter(line -> !line.text().isBlank()).findFirst().orElse(null);
			if (firstLine == null) {
				builder.finishParagraph();
				builder.finishList();
				builder.finishTable();
				continue;
			}
			if (builder.hasParagraph()
					&& !continuesAcrossPage(builder.previousLine(), firstLine, builder.paragraphStartX(),
							builder.paragraphType(), typicalFontSize)) {
				builder.finishParagraph();
			}
			if (builder.hasList() && !continuesListAcrossPage(firstLine, builder.lastListLine(), leftMargin,
					typicalFontSize)) {
				builder.finishList();
			}
			builder.finishTable();
			for (int index = 0; index < visibleLines.size(); index++) {
				PositionedLine line = visibleLines.get(index);
				PositionedLine previous = index > 0 ? visibleLines.get(index - 1) : null;
				PositionedLine next = index + 1 < visibleLines.size() ? visibleLines.get(index + 1) : null;
				String value = line.text();
				if (value.isBlank()) {
					continue;
				}
				if (TOC_TITLE.matcher(value).matches()) {
					builder.finishParagraph();
					builder.finishList();
					builder.addBlock("h2", value, line);
					inContents = true;
					continue;
				}
				if (inContents && isContentsEntry(value)) {
					builder.finishParagraph();
					builder.finishList();
					builder.finishTable();
					builder.addBlock("toc", normalizeContentsEntry(value), line);
					continue;
				}
				if (inContents && isStructuralHeading(value)) {
					inContents = false;
				}
				if (next != null && isFragmentedRomanHeading(line, next, typicalFontSize)) {
					builder.finishParagraph();
					builder.finishList();
					String type = isStructuralHeading(next.text()) ? headingType(next, typicalFontSize) : "h2";
					builder.addBlock(type, value + " " + next.text(), line, next);
					index++;
					continue;
				}

				if (line.tableRow()) {
					builder.finishParagraph();
					builder.finishList();
					builder.addTableRow(value, line);
					continue;
				}
				builder.finishTable();

				if (line.fontSize() <= typicalFontSize * 0.78 && line.y() > line.pageHeight() * 0.78) {
					builder.finishParagraph();
					builder.finishList();
					builder.addBlock("footnote", value, line);
					continue;
				}

				Matcher listItem = LIST_ITEM.matcher(value);
				if (listItem.matches()) {
					builder.startList(value.matches("^(?:\\d+|[A-Za-z])[.)]\\s+.*$") ? "ol" : "ul");
					builder.addListItem(listItem.group(1), line);
					continue;
				}

				if (builder.hasList() && builder.isListContinuation(line, leftMargin, typicalFontSize)) {
					builder.appendToLastListItem(joinLines(builder.lastListItem(), value), line);
					continue;
				}
				builder.finishList();

				if (isHeading(line, previous, next, typicalFontSize)) {
					builder.finishParagraph();
					builder.addBlock(headingType(line, typicalFontSize), value, line);
					continue;
				}

				boolean codeContinuation = builder.hasParagraph() && builder.paragraphType().equals("code")
						&& line.monospace();
				boolean quote = line.italic() && line.x() > leftMargin + Math.max(12, typicalFontSize)
						|| value.matches("^[“‘\"].*[”’\"]$");
				String blockType = codeContinuation || line.monospace() && (line.x() > leftMargin + typicalFontSize
						|| value.matches(".*[{};=<>].*")) ? "code" : quote ? "blockquote" : "p";
				if (builder.hasParagraph() && (!builder.paragraphType().equals(blockType)
						|| startsNewParagraph(line, builder.previousLine(), builder.paragraphStartX(), typicalFontSize))) {
					builder.finishParagraph();
				}
				builder.appendParagraph(value, blockType, line);

				if (next != null && (startsNewParagraph(next, line, builder.paragraphStartX(), typicalFontSize)
						|| isHeading(next, line, index + 2 < visibleLines.size() ? visibleLines.get(index + 2) : null,
								typicalFontSize)
						|| LIST_ITEM.matcher(next.text()).matches()
						|| next.tableRow() || next.monospace() != line.monospace())) {
					builder.finishParagraph();
				}
			}
		}

		builder.finishParagraph();
		builder.finishList();
		builder.finishTable();
		return List.copyOf(blocks);
	}

	private boolean isContentsEntry(String text) {
		return text.matches(".*(?:\\.{2,}|·{2,}|…|\\s+\\d{1,4})\\s*$");
	}

	private String normalizeContentsEntry(String text) {
		return TOC_LEADER.matcher(text).replaceAll(" — ").replaceAll("\\s+", " ").strip();
	}

	private boolean isStructuralHeading(String text) {
		return STRUCTURAL_HEADING.matcher(text.strip()).matches();
	}

	private String headingType(PositionedLine line, double typicalFontSize) {
		String value = line.text().strip();
		if (value.matches("(?i)^part\\s+.*") || value.matches("(?i)^(?:prologue|epilogue)$")) {
			return "h1";
		}
		if (value.matches("(?i)^section\\s+.*")) {
			return "h3";
		}
		if (value.matches("(?i)^chapter\\s+.*") || value.matches("(?i)^(?:introduction|conclusion|appendix)\\b.*")) {
			return "h2";
		}
		return line.fontSize() >= typicalFontSize * 1.95 ? "h1"
				: line.fontSize() >= typicalFontSize * 1.3 ? "h2" : "h3";
	}

	private boolean continuesAcrossPage(
			PositionedLine previous, PositionedLine first, double paragraphStartX, String paragraphType,
			double typicalFontSize) {
		if (previous == null || first == null || paragraphType == null
				|| !paragraphType.equals("p") && !paragraphType.equals("blockquote")
				|| previous.pageNumber() == first.pageNumber()
				|| previous.pageHeight() != first.pageHeight()
				|| previous.y() < previous.pageHeight() * 0.6
				|| first.y() > first.pageHeight() * 0.4
				|| previous.monospace() || first.monospace()
				|| Math.abs(previous.fontSize() - first.fontSize()) > typicalFontSize * 0.12
				|| Math.abs(first.x() - paragraphStartX) > typicalFontSize * 1.4
				|| endsSentence(previous.text())) {
			return false;
		}
		return first.text().isEmpty() || Character.isLowerCase(first.text().codePointAt(0))
				|| Character.isDigit(first.text().codePointAt(0))
				|| startsWithContinuationPunctuation(first.text());
	}

	private boolean continuesListAcrossPage(
			PositionedLine first, PositionedLine last, double leftMargin, double typicalFontSize) {
		return first != null && last != null && first.pageNumber() != last.pageNumber()
				&& last.y() > last.pageHeight() * 0.6 && first.y() < first.pageHeight() * 0.4
				&& LIST_ITEM.matcher(first.text()).matches()
				&& Math.abs(first.fontSize() - last.fontSize()) <= typicalFontSize * 0.15
				&& Math.abs(first.x() - last.x()) <= typicalFontSize * 2;
	}

	private boolean endsSentence(String text) {
		return text.stripTrailing().matches(".*[.!?…][\"”’')\\]]*$");
	}

	private boolean startsWithContinuationPunctuation(String text) {
		return text.matches("^[,.;:!?)}\\]].*");
	}

	private List<PositionedLine> orderForReading(List<PositionedLine> lines) {
		List<PositionedLine> verticalOrder = lines.stream()
				.sorted(Comparator.comparingDouble(PositionedLine::y).thenComparingDouble(PositionedLine::x))
				.toList();
		Double gutter = findColumnGutter(verticalOrder);
		if (gutter == null) {
			return verticalOrder;
		}

		List<PositionedLine> ordered = new ArrayList<>();
		List<PositionedLine> zone = new ArrayList<>();
		for (PositionedLine line : verticalOrder) {
			if (line.tableRow() || line.x() < gutter && line.endX() > gutter) {
				appendColumns(ordered, zone, gutter);
				zone.clear();
				ordered.add(line);
			}
			else {
				zone.add(line);
			}
		}
		appendColumns(ordered, zone, gutter);
		return ordered;
	}

	private Double findColumnGutter(List<PositionedLine> lines) {
		if (lines.size() < 4) {
			return null;
		}

		double pageWidth = lines.get(0).pageWidth();
		double bestScore = 0;
		Double bestGutter = null;
		List<PositionedLine> columnLines = lines.stream().filter(line -> !line.tableRow()).toList();
		for (PositionedLine left : lines) {
			for (PositionedLine right : lines) {
				double gap = right.x() - left.endX();
				double gutter = left.endX() + gap / 2;
				if (gap < Math.max(24, pageWidth * 0.035) || gutter < pageWidth * 0.2
						|| gutter > pageWidth * 0.8) {
					continue;
				}
				long leftCount = columnLines.stream().filter(line -> line.endX() <= gutter).count();
				long rightCount = columnLines.stream().filter(line -> line.x() >= gutter).count();
				long crossingCount = columnLines.size() - leftCount - rightCount;
				if (leftCount < 2 || rightCount < 2 || crossingCount > Math.max(1, columnLines.size() / 8)
						|| !verticalRangesOverlap(columnLines, gutter)) {
					continue;
				}
				double balance = (double) Math.min(leftCount, rightCount) / Math.max(leftCount, rightCount);
				double score = gap * (0.75 + balance * 0.25);
				if (score > bestScore) {
					bestScore = score;
					bestGutter = gutter;
				}
			}
		}
		return bestGutter;
	}

	private boolean verticalRangesOverlap(List<PositionedLine> lines, double gutter) {
		List<PositionedLine> left = lines.stream().filter(line -> line.endX() <= gutter).toList();
		List<PositionedLine> right = lines.stream().filter(line -> line.x() >= gutter).toList();
		double overlapStart = Math.max(left.stream().mapToDouble(PositionedLine::y).min().orElse(0),
				right.stream().mapToDouble(PositionedLine::y).min().orElse(0));
		double overlapEnd = Math.min(left.stream().mapToDouble(PositionedLine::y).max().orElse(0),
				right.stream().mapToDouble(PositionedLine::y).max().orElse(0));
		return overlapEnd - overlapStart > 0;
	}

	private void appendColumns(List<PositionedLine> ordered, List<PositionedLine> zone, double gutter) {
		zone.stream().filter(line -> line.endX() <= gutter)
				.sorted(Comparator.comparingDouble(PositionedLine::y).thenComparingDouble(PositionedLine::x))
				.forEach(ordered::add);
		zone.stream().filter(line -> line.x() >= gutter)
				.sorted(Comparator.comparingDouble(PositionedLine::y).thenComparingDouble(PositionedLine::x))
				.forEach(ordered::add);
	}

	private List<PositionedLine> removeDuplicateLines(List<PositionedLine> lines) {
		List<PositionedLine> unique = new ArrayList<>();
		for (PositionedLine line : lines) {
			boolean duplicateAtSamePosition = unique.stream().anyMatch(existing ->
					existing.text().equals(line.text()) && Math.abs(existing.x() - line.x()) <= 2
							&& Math.abs(existing.y() - line.y()) <= 2);
			if (!duplicateAtSamePosition) {
				unique.add(line);
			}
		}
		return unique;
	}

	private List<PositionedLine> combineTableRows(List<PositionedLine> lines) {
		List<List<PositionedLine>> baselines = new ArrayList<>();
		for (PositionedLine line : lines.stream()
				.sorted(Comparator.comparingDouble(PositionedLine::y).thenComparingDouble(PositionedLine::x))
				.toList()) {
			if (baselines.isEmpty()
					|| Math.abs(baselines.get(baselines.size() - 1).get(0).y() - line.y()) > 2) {
				baselines.add(new ArrayList<>());
			}
			baselines.get(baselines.size() - 1).add(line);
		}

		boolean[] tableBaseline = new boolean[baselines.size()];
		for (int first = 0; first < baselines.size(); first++) {
			List<PositionedLine> candidate = baselines.get(first);
			if (candidate.size() < 3) {
				continue;
			}
			List<Integer> matching = new ArrayList<>();
			for (int other = first + 1; other < baselines.size(); other++) {
				List<PositionedLine> comparison = baselines.get(other);
				if (comparison.size() == candidate.size()
						&& alignedColumns(candidate, comparison)) {
					matching.add(other);
				}
			}
			if (!matching.isEmpty()) {
				tableBaseline[first] = true;
				matching.forEach(index -> tableBaseline[index] = true);
			}
		}

		List<PositionedLine> combined = new ArrayList<>();
		for (int index = 0; index < baselines.size(); index++) {
			List<PositionedLine> baseline = baselines.get(index);
			if (tableBaseline[index]) {
				combined.add(combineTableRow(baseline));
			}
			else {
				combined.addAll(baseline);
			}
		}
		return combined;
	}

	private boolean alignedColumns(List<PositionedLine> first, List<PositionedLine> second) {
		for (int index = 0; index < first.size(); index++) {
			if (Math.abs(first.get(index).x() - second.get(index).x()) > 4) {
				return false;
			}
		}
		return true;
	}

	private PositionedLine combineTableRow(List<PositionedLine> fragments) {
		List<PositionedLine> ordered = fragments.stream()
				.sorted(Comparator.comparingDouble(PositionedLine::x)).toList();
		String text = ordered.stream().map(PositionedLine::text).reduce((left, right) -> left + "\t" + right)
				.orElse("");
		PositionedLine first = ordered.get(0);
		PositionedLine last = ordered.get(ordered.size() - 1);
		return new PositionedLine(text, first.x(), last.endX(), first.y(),
				ordered.stream().mapToDouble(PositionedLine::fontSize).average().orElse(first.fontSize()),
				first.pageHeight(), first.pageWidth(), first.pageNumber(),
				ordered.stream().anyMatch(PositionedLine::bold), false, false, true);
	}

	private Map<String, Integer> repeatedPageLines(List<List<PositionedLine>> pages) {
		Map<String, Integer> pageCounts = new HashMap<>();
		for (List<PositionedLine> page : pages) {
			page.stream().filter(this::isNearPageEdge).map(this::repeatedLineKey).distinct()
					.forEach(key -> pageCounts.merge(key, 1, Integer::sum));
		}

		int minimumPageCount = Math.max(2, (int) Math.ceil(pages.size() * 0.5));
		pageCounts.entrySet().removeIf(entry -> entry.getValue() < minimumPageCount);
		return pageCounts;
	}

	private boolean isRepeatedPageLine(PositionedLine line, Map<String, Integer> repeatedLines) {
		return isNearPageEdge(line) && repeatedLines.containsKey(repeatedLineKey(line));
	}

	private boolean isNearPageEdge(PositionedLine line) {
		return line.y() <= line.pageHeight() * 0.12 || line.y() >= line.pageHeight() * 0.88;
	}

	private String repeatedLineKey(PositionedLine line) {
		String normalized = line.text().toLowerCase(Locale.ROOT);
		String value = normalized.matches("^(?:page\\s*)?\\d{1,4}$") ? "page-number" : normalized;
		String edge = line.y() <= line.pageHeight() * 0.12 ? "top" : "bottom";
		int verticalBand = (int) Math.round(line.y() / line.pageHeight() * 20);
		int horizontalBand = (int) Math.round(line.x() / line.pageWidth() * 20);
		return value + "|" + edge + "|" + verticalBand + "|" + horizontalBand;
	}

	private boolean isHeading(
			PositionedLine line, PositionedLine previous, PositionedLine next, double typicalFontSize) {
		String text = line.text().strip();
		if (text.length() > 100 || text.isEmpty() || text.matches(".*[.!?;:]$")) {
			return false;
		}
		if (isStructuralHeading(text)) {
			return true;
		}
		boolean spacedBefore = previous != null && line.y() - previous.y() > typicalFontSize * 1.7;
		boolean spacedAfter = next != null && next.y() - line.y() > typicalFontSize * 1.7;
		boolean spaced = spacedBefore || spacedAfter;
		boolean compactHeading = text.split("\\s+").length <= 8;
		return compactHeading && line.fontSize() >= typicalFontSize * 1.45
				|| compactHeading && line.bold() && line.fontSize() >= typicalFontSize * 1.05 && spaced
				|| compactHeading && isAllCapsHeading(text) && text.length() <= 55 && spaced;
	}

	private boolean isFragmentedRomanHeading(
			PositionedLine numeral, PositionedLine title, double typicalFontSize) {
		return numeral.text().matches("(?i)^(?:[ivxlcdm]{1,8})\\.?$")
				&& Math.abs(numeral.x() - title.x()) <= typicalFontSize
				&& title.y() - numeral.y() <= typicalFontSize * 2.5
				&& (numeral.bold() || title.bold() || title.fontSize() >= typicalFontSize * 1.2
						|| title.text().equals(title.text().toUpperCase(Locale.ROOT))
								&& title.text().matches(".*\\p{L}.*"));
	}

	private boolean isAllCapsHeading(String text) {
		String letters = text.replaceAll("[^\\p{L}]", "");
		return letters.length() >= 3 && letters.equals(letters.toUpperCase(Locale.ROOT))
				&& !text.matches(".*[.!?;:]$");
	}

	private boolean startsNewParagraph(
			PositionedLine current, PositionedLine previous, double paragraphStartX, double typicalFontSize) {
		double lineGap = current.y() - previous.y();
		if (current.monospace() && previous.monospace()) {
			return lineGap > Math.max(current.fontSize(), previous.fontSize()) * 2.2;
		}
		return lineGap > Math.max(current.fontSize(), previous.fontSize()) * 1.55
				|| current.x() < previous.x() - typicalFontSize
				|| current.x() > paragraphStartX + typicalFontSize * 1.4
						&& lineGap > typicalFontSize * 1.1;
	}

	private String joinLines(String previous, String next) {
		if (previous.endsWith("\u00ad") && !next.isEmpty() && Character.isLowerCase(next.codePointAt(0))) {
			return previous.substring(0, previous.length() - 1) + next;
		}
		if (previous.matches(".*[A-Za-z]-$") && !next.isEmpty() && Character.isLowerCase(next.codePointAt(0))) {
			return isLikelyDiscretionaryHyphen(previous, next)
					? previous.substring(0, previous.length() - 1) + next
					: previous + next;
		}
		return previous + " " + next;
	}

	private boolean isLikelyDiscretionaryHyphen(String previous, String next) {
		String left = previous.substring(previous.lastIndexOf(' ') + 1, previous.length() - 1).toLowerCase(Locale.ROOT);
		String right = next.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
		if (left.length() < 3 || right.length() < 3) {
			return false;
		}
		return !List.of("known", "being", "bred", "proof", "made", "based", "wide", "shaped",
				"life", "long", "term", "class", "worker", "time", "century", "friendly", "minded",
				"speaking", "reading", "standing", "looking", "to").contains(right);
	}

	private double median(double[] values) {
		if (values.length == 0) {
			return 12;
		}
		double[] sorted = values.clone();
		java.util.Arrays.sort(sorted);
		int middle = sorted.length / 2;
		return sorted.length % 2 == 0 ? (sorted[middle - 1] + sorted[middle]) / 2 : sorted[middle];
	}

	private String normalizeWhitespace(String text) {
		return text.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
	}

	private final class PositionAwareTextStripper extends PDFTextStripper {

		private final List<List<PositionedLine>> pages = new ArrayList<>();

		PositionAwareTextStripper() throws IOException {
		}

		@Override
		protected void startPage(org.apache.pdfbox.pdmodel.PDPage page) throws IOException {
			super.startPage(page);
			this.pages.add(new ArrayList<>());
		}

		@Override
		protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
			String normalized = normalizeWhitespace(text);
			if (normalized.isEmpty() || textPositions.isEmpty()) {
				return;
			}

			double x = textPositions.stream().mapToDouble(TextPosition::getXDirAdj).min().orElse(0);
			double endX = textPositions.stream()
					.mapToDouble(position -> position.getXDirAdj() + position.getWidthDirAdj())
					.max().orElse(x);
			double y = textPositions.stream().mapToDouble(TextPosition::getYDirAdj).min().orElse(0);
			double fontSize = textPositions.stream().mapToDouble(TextPosition::getFontSizeInPt).average().orElse(12);
			double pageHeight = textPositions.get(0).getPageHeight();
			double pageWidth = textPositions.get(0).getPageWidth();
			boolean bold = textPositions.stream().anyMatch(position ->
					position.getFont() != null && position.getFont().getName().toLowerCase(Locale.ROOT).contains("bold"));
			boolean italic = textPositions.stream().anyMatch(position ->
					position.getFont() != null && position.getFont().getName().toLowerCase(Locale.ROOT)
							.matches(".*(italic|oblique).*"));
			boolean monospace = textPositions.stream().allMatch(position ->
					position.getFont() != null && position.getFont().getName().toLowerCase(Locale.ROOT)
							.matches(".*(courier|mono|typewriter).*"));
			int largeGaps = 0;
			StringBuilder tableText = new StringBuilder();
			TextPosition previous = null;
			for (TextPosition position : textPositions) {
				if (previous != null) {
					double gap = position.getXDirAdj() - (previous.getXDirAdj() + previous.getWidthDirAdj());
					if (gap > fontSize * 1.5) {
						largeGaps++;
						tableText.append('\t');
					}
				}
				tableText.append(position.getUnicode());
				previous = position;
			}
			boolean tableRow = largeGaps >= 2;
			String lineText = tableRow ? normalizeWhitespace(tableText.toString().replace('\t', ' ')) : normalized;
			if (tableRow) {
				lineText = tableText.toString().trim();
			}
			this.pages.get(this.pages.size() - 1)
					.add(new PositionedLine(lineText, x, endX, y, fontSize, pageHeight, pageWidth,
							this.pages.size(), bold, italic, monospace, tableRow));
		}

		List<List<PositionedLine>> pages() {
			return this.pages.stream().map(List::copyOf).toList();
		}
	}

	private record PositionedLine(
			String text, double x, double endX, double y, double fontSize, double pageHeight, double pageWidth,
			int pageNumber, boolean bold, boolean italic, boolean monospace, boolean tableRow) {
	}

	private final class BlockBuilder {

		private final List<PdfTextBlock> blocks;
		private final List<String> listItems = new ArrayList<>();
		private String listType;
		private final List<String> tableRows = new ArrayList<>();
		private StringBuilder paragraph = new StringBuilder();
		private String paragraphType;
		private PositionedLine previousLine;
		private PositionedLine lastListLine;
		private double paragraphStartX;
		private int paragraphStartPage;
		private int paragraphEndPage;
		private int listStartPage;
		private int listEndPage;
		private int tableStartPage;
		private int tableEndPage;

		BlockBuilder(List<PdfTextBlock> blocks) {
			this.blocks = blocks;
		}

		void appendParagraph(String text, String type, PositionedLine line) {
			if (!this.paragraph.isEmpty()) {
				if (this.paragraphType.equals("code")) {
					int indentation = Math.max(0, (int) Math.round(
							(line.x() - this.paragraphStartX) / Math.max(1, line.fontSize()) * 2));
					this.paragraph.append("\n").append(" ".repeat(indentation)).append(text);
				}
				else {
					this.paragraph = new StringBuilder(joinLines(this.paragraph.toString(), text));
				}
			}
			else {
				this.paragraph = new StringBuilder(text);
				this.paragraphStartX = line.x();
				this.paragraphStartPage = line.pageNumber();
			}
			this.paragraphType = type;
			this.previousLine = line;
			this.paragraphEndPage = line.pageNumber();
		}

		boolean hasParagraph() {
			return !this.paragraph.isEmpty();
		}

		String paragraphType() {
			return this.paragraphType;
		}

		PositionedLine previousLine() {
			return this.previousLine;
		}

		double paragraphStartX() {
			return this.paragraphStartX;
		}

		void finishParagraph() {
			if (hasParagraph()) {
				this.blocks.add(new PdfTextBlock(
						this.paragraphType, this.paragraph.toString(), this.paragraphStartPage, this.paragraphEndPage));
				this.paragraph = new StringBuilder();
				this.paragraphType = null;
				this.previousLine = null;
				this.paragraphStartX = 0;
				this.paragraphStartPage = 0;
				this.paragraphEndPage = 0;
			}
		}

		void addBlock(String type, String text, PositionedLine line) {
			this.blocks.add(new PdfTextBlock(type, text, line.pageNumber()));
		}

		void addBlock(String type, String text, PositionedLine first, PositionedLine last) {
			this.blocks.add(new PdfTextBlock(type, text, first.pageNumber(), last.pageNumber()));
		}

		void startList(String type) {
			if (hasList() && !this.listType.equals(type)) {
				finishList();
			}
			finishParagraph();
			this.listType = type;
		}

		void addListItem(String text, PositionedLine line) {
			if (this.listItems.isEmpty()) {
				this.listStartPage = line.pageNumber();
			}
			this.listItems.add(text);
			this.lastListLine = line;
			this.listEndPage = line.pageNumber();
		}

		boolean hasList() {
			return !this.listItems.isEmpty();
		}

		String lastListItem() {
			return this.listItems.get(this.listItems.size() - 1);
		}

		PositionedLine lastListLine() {
			return this.lastListLine;
		}

		boolean isListContinuation(PositionedLine line, double leftMargin, double typicalFontSize) {
			return this.lastListLine != null && line.x() > leftMargin + typicalFontSize
					&& line.y() - this.lastListLine.y() <= typicalFontSize * 1.55;
		}

		void appendToLastListItem(String text, PositionedLine line) {
			this.listItems.set(this.listItems.size() - 1, text);
			this.lastListLine = line;
			this.listEndPage = line.pageNumber();
		}

		void finishList() {
			if (hasList()) {
				this.blocks.add(new PdfTextBlock(
						this.listType, String.join("\n", this.listItems), this.listStartPage, this.listEndPage));
				this.listItems.clear();
				this.lastListLine = null;
				this.listType = null;
				this.listStartPage = 0;
				this.listEndPage = 0;
			}
		}

		void addTableRow(String text, PositionedLine line) {
			if (this.tableRows.isEmpty()) {
				this.tableStartPage = line.pageNumber();
			}
			this.tableRows.add(text);
			this.tableEndPage = line.pageNumber();
		}

		void finishTable() {
			if (!this.tableRows.isEmpty()) {
				this.blocks.add(new PdfTextBlock(
						"table", String.join("\n", this.tableRows), this.tableStartPage, this.tableEndPage));
				this.tableRows.clear();
				this.tableStartPage = 0;
				this.tableEndPage = 0;
			}
		}
	}

}
