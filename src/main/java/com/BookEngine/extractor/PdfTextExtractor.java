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

		for (List<PositionedLine> page : pages) {
			List<PositionedLine> pageContent = page.stream()
					.filter(line -> !isRepeatedPageLine(line, repeatedPageLines))
					.toList();
			List<PositionedLine> visibleLines = orderForReading(
					combineTableRows(removeDuplicateLines(pageContent)));
			double leftMargin = visibleLines.stream().mapToDouble(PositionedLine::x).min().orElse(0);

			for (int index = 0; index < visibleLines.size(); index++) {
				PositionedLine line = visibleLines.get(index);
				PositionedLine previous = index > 0 ? visibleLines.get(index - 1) : null;
				PositionedLine next = index + 1 < visibleLines.size() ? visibleLines.get(index + 1) : null;
				String value = line.text();
				if (value.isBlank()) {
					continue;
				}

				if (line.tableRow()) {
					builder.finishParagraph();
					builder.finishList();
					builder.addTableRow(value);
					continue;
				}
				builder.finishTable();

				if (line.fontSize() <= typicalFontSize * 0.78 && line.y() > line.pageHeight() * 0.78) {
					builder.finishParagraph();
					builder.finishList();
					builder.addBlock("footnote", value);
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
					builder.addBlock("h2", value);
					continue;
				}

				boolean codeContinuation = builder.hasParagraph() && builder.paragraphType().equals("code")
						&& line.monospace();
				String blockType = codeContinuation || line.monospace() && (line.x() > leftMargin + typicalFontSize
						|| value.matches(".*[{};=<>].*"))
						? "code"
						: line.x() > leftMargin + Math.max(24, typicalFontSize * 2) ? "blockquote" : "p";
				if (builder.hasParagraph() && (!builder.paragraphType().equals(blockType)
						|| startsNewParagraph(line, builder.previousLine(), builder.paragraphStartX(), typicalFontSize))) {
					builder.finishParagraph();
				}
				builder.appendParagraph(value, blockType, line);

				if (next == null || startsNewParagraph(next, line, builder.paragraphStartX(), typicalFontSize)
						|| isHeading(next, line, index + 2 < visibleLines.size() ? visibleLines.get(index + 2) : null,
								typicalFontSize)
						|| LIST_ITEM.matcher(next.text()).matches()
						|| next.tableRow() || next.monospace() != line.monospace()) {
					builder.finishParagraph();
				}
			}
			builder.finishPage();
		}

		builder.finishPage();
		return List.copyOf(blocks);
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
				first.pageHeight(), first.pageWidth(),
				ordered.stream().anyMatch(PositionedLine::bold), false, true);
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
		if (line.text().length() > 80) {
			return false;
		}
		boolean spaced = previous == null || line.y() - previous.y() > typicalFontSize * 1.5
				|| next == null || next.y() - line.y() > typicalFontSize * 1.5;
		return line.fontSize() >= typicalFontSize * 1.25
				|| line.bold() && line.fontSize() >= typicalFontSize * 1.05 && (spaced || line.text().length() <= 45)
				|| isAllCapsHeading(line.text()) && line.text().length() <= 55 && spaced;
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
		if (previous.matches(".*[-\\u00ad]$") && !next.isEmpty() && Character.isLowerCase(next.charAt(0))) {
			return previous.substring(0, previous.length() - 1) + next;
		}
		return previous + " " + next;
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
							bold, monospace, tableRow));
		}

		List<List<PositionedLine>> pages() {
			return this.pages.stream().map(List::copyOf).toList();
		}
	}

	private record PositionedLine(
			String text, double x, double endX, double y, double fontSize, double pageHeight, double pageWidth,
			boolean bold, boolean monospace, boolean tableRow) {
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
			}
			this.paragraphType = type;
			this.previousLine = line;
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
				this.blocks.add(new PdfTextBlock(this.paragraphType, this.paragraph.toString()));
				this.paragraph = new StringBuilder();
				this.paragraphType = null;
				this.previousLine = null;
				this.paragraphStartX = 0;
			}
		}

		void addBlock(String type, String text) {
			this.blocks.add(new PdfTextBlock(type, text));
		}

		void startList(String type) {
			if (hasList() && !this.listType.equals(type)) {
				finishList();
			}
			finishParagraph();
			this.listType = type;
		}

		void addListItem(String text, PositionedLine line) {
			this.listItems.add(text);
			this.lastListLine = line;
		}

		boolean hasList() {
			return !this.listItems.isEmpty();
		}

		String lastListItem() {
			return this.listItems.get(this.listItems.size() - 1);
		}

		boolean isListContinuation(PositionedLine line, double leftMargin, double typicalFontSize) {
			return this.lastListLine != null && line.x() > leftMargin + typicalFontSize
					&& line.y() - this.lastListLine.y() <= typicalFontSize * 1.55;
		}

		void appendToLastListItem(String text, PositionedLine line) {
			this.listItems.set(this.listItems.size() - 1, text);
			this.lastListLine = line;
		}

		void finishList() {
			if (hasList()) {
				this.blocks.add(new PdfTextBlock(this.listType, String.join("\n", this.listItems)));
				this.listItems.clear();
				this.lastListLine = null;
				this.listType = null;
			}
		}

		void addTableRow(String text) {
			this.tableRows.add(text);
		}

		void finishTable() {
			if (!this.tableRows.isEmpty()) {
				this.blocks.add(new PdfTextBlock("table", String.join("\n", this.tableRows)));
				this.tableRows.clear();
			}
		}

		void finishPage() {
			finishParagraph();
			finishList();
			finishTable();
		}
	}

}
