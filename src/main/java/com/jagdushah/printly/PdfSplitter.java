package com.jagdushah.printly;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Rewriting a PDF down to the pages a rule selected, and reading its page count.
 *
 * <p>The counterpart to {@link PageSelection}: the selection decides which pages, this produces
 * the bytes. Rewriting rather than asking the driver for a page range is the point &mdash; see the
 * note on {@link PageSelection} &mdash; and it is also what makes a strategy composable, because
 * once a rule's pages are their own document every downstream option (geometry, copies, preview,
 * reprint) applies to exactly those pages with no special cases anywhere.
 *
 * <p>Cost is real but small next to a print: on a warehouse PC a load-select-save of a two-page
 * invoice is single-digit milliseconds, against tens of milliseconds to rasterise it and seconds
 * for the head to put it on paper. It is still skipped outright when the selection is everything,
 * which is the common case and the one the pack flow's hot path takes.
 */
public final class PdfSplitter {

    private PdfSplitter() {
    }

    /** What a document is, before any rule has been applied to it. */
    public record Info(int pageCount, double widthPt, double heightPt) {

        /** True for a page wider than it is tall, which is what the auto-landscape flip reads. */
        public boolean landscape() {
            return widthPt > heightPt;
        }
    }

    /**
     * Page count and first-page size, without rendering anything.
     *
     * <p>The batch screen needs the page count before it can show what a strategy will do with a
     * file, and it needs it for every dropped file at once. Loading the document structure is
     * cheap; rendering is not, and nothing here renders.
     */
    public static Info inspect(byte[] pdf) throws IOException {
        try (PDDocument doc = PDDocument.load(pdf)) {
            int pages = doc.getNumberOfPages();
            if (pages == 0) {
                throw new IOException("the PDF has no pages");
            }
            PDPage first = doc.getPage(0);
            var box = first.getMediaBox();
            // A page can carry a /Rotate of 90 or 270, in which case the media box is still the
            // unrotated one and reading it directly would call a landscape page portrait. The
            // auto-landscape detection downstream reads the effective size, so this must too.
            int rotation = ((first.getRotation() % 360) + 360) % 360;
            boolean swapped = rotation == 90 || rotation == 270;
            double w = swapped ? box.getHeight() : box.getWidth();
            double h = swapped ? box.getWidth() : box.getHeight();
            return new Info(pages, w, h);
        }
    }

    /**
     * The result of applying a selection: the bytes to print, and what they came from.
     *
     * @param pdf         the document to print, which is the original array when nothing was cut
     * @param sourcePages how many pages the document had before the selection
     * @param pages       the 1-based pages that survived, in the order they will print
     * @param rewritten   false when the selection was the whole document and nothing was re-encoded
     */
    public record Applied(byte[] pdf, int sourcePages, List<Integer> pages, boolean rewritten) {
    }

    /**
     * Resolve a selection against a document and rewrite it down to the pages that survive.
     *
     * <p>One load for both halves of the question. Resolving needs the page count, which needs the
     * document open, and rewriting needs it open too; doing them separately meant parsing every
     * PDF twice on a path that runs once per file in a bulk run.
     *
     * <p>Returns the original array untouched when the selection is already the whole document in
     * order, which skips a re-encode that is otherwise the slowest step of a bulk run.
     *
     * @throws IOException              if the document cannot be read or written
     * @throws IllegalArgumentException if the selection matches no page of this document
     */
    public static Applied apply(byte[] pdf, PageSelection selection) throws IOException {
        try (PDDocument source = PDDocument.load(pdf)) {
            int count = source.getNumberOfPages();
            if (count == 0) {
                throw new IOException("the PDF has no pages");
            }
            List<Integer> pages = selection.resolve(count);
            if (pages.isEmpty()) {
                throw new IllegalArgumentException("page selection '" + selection.spec()
                        + "' matches no page of this " + count + "-page document");
            }
            if (isWholeDocumentInOrder(pages, count)) {
                return new Applied(pdf, count, pages, false);
            }
            try (PDDocument out = new PDDocument()) {
                // importPage plus setResources, which is exactly what PDFBox's own Splitter does.
                // importPage alone copies the page dictionary and content stream but leaves the
                // resources referenced in the source document, and addPage does not even copy the
                // content stream. Either shortcut can produce a PDF that opens without complaint
                // and prints blank — the worst shape of failure available here, because nothing
                // downstream reports an error and the paper is already gone.
                for (int p : pages) {
                    PDPage src = source.getPage(p - 1);
                    PDPage imported = out.importPage(src);
                    imported.setResources(src.getResources());
                    dropLinksToDroppedPages(imported);
                }
                // The save must happen while the source is still open: the imported pages'
                // resources are its objects, and PDFBox resolves them lazily on write.
                ByteArrayOutputStream buf = new ByteArrayOutputStream(Math.max(4096, pdf.length / 2));
                out.save(buf);
                return new Applied(buf.toByteArray(), count, pages, true);
            }
        }
    }

    /**
     * Clear internal jump targets on an imported page's annotations.
     *
     * <p>A link whose destination is a page that did not come along resolves to nothing in the new
     * document, and a dangling destination is one of the few things a strict PDF consumer will
     * refuse the whole file over. Nothing printed here is interactive, so dropping the action is
     * free; PDFBox's Splitter does the same.
     */
    private static void dropLinksToDroppedPages(PDPage page) throws IOException {
        for (PDAnnotation annotation : page.getAnnotations()) {
            if (!(annotation instanceof PDAnnotationLink link)) {
                continue;
            }
            PDAction action = link.getAction();
            if (action instanceof PDActionGoTo) {
                link.setAction(null);
            }
            link.setDestination(null);
        }
    }

    private static boolean isWholeDocumentInOrder(List<Integer> pages, int count) {
        if (pages.size() != count) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            if (pages.get(i) != i + 1) {
                return false;
            }
        }
        return true;
    }
}
