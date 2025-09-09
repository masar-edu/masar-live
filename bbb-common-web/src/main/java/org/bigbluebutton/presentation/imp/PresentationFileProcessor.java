package org.bigbluebutton.presentation.imp;

import com.amazonaws.services.s3.model.S3Object;
import com.google.gson.Gson;
import org.apache.commons.io.FilenameUtils;
import org.bigbluebutton.api.Util;
import org.bigbluebutton.api.domain.Meeting;
import org.bigbluebutton.api.service.ServiceUtils;
import org.bigbluebutton.presentation.*;
import org.bigbluebutton.presentation.messages.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

// Presentation files are processed using two separate thread pools.
// One thread pool handles the preparation of PDF and image documents
// for conversion along with the actual conversion of image documents.
// The second thread pool handles the conversion of PDF document pages.
// A PDF with multiple pages that take a long time to convert may saturate
// the second thread pool effectively blocking the upload of further PDF
// documents. There is a trade-off between converting multiple pages at once
// versus uploading multiple documents at once and BBB has chosen to convert
// pages more quickly at the expense of possibly not being able to upload
// multiple documents at once.

public class PresentationFileProcessor {
    private static Logger log = LoggerFactory.getLogger(PresentationFileProcessor.class);

    private boolean svgImagesRequired=true;
    private boolean generatePngs;
    private PageExtractor pageExtractor;

    private long bigPdfSize;
    private long maxBigPdfPageSize;

    private TextFileCreator textFileCreator;
    private SvgImageCreator svgImageCreator;
    private ThumbnailCreator thumbnailCreator;
    private PngCreator pngCreator;
    private SlidesGenerationProgressNotifier notifier;
    private PageCounterService counterService;
    private PresentationConversionCompletionService presentationConversionCompletionService;
    private ImageSlidesGenerationService imageSlidesGenerationService;
    private PdfSlidesGenerationService pdfSlidesGenerationService;
    private S3FileManager s3FileManager;

    private final ExecutorService executor;
    private final ExecutorService supervisor;
    private volatile boolean processPresentation = false;

    private BlockingQueue<UploadedPresentation> presentations = new LinkedBlockingQueue<UploadedPresentation>();

    public PresentationFileProcessor(int numConversionThreads) {
        executor = Executors.newFixedThreadPool(numConversionThreads);
        supervisor = Executors.newFixedThreadPool(2 * numConversionThreads);
    }

    public synchronized void process(UploadedPresentation pres) {
        if (pres.isDownloadable()) {
            processMakePresentationDownloadableMsg(pres);
        }

        String meetingId = pres.getMeetingId();
        //Download presentation outputs from cache (if enabled)
        try {
            pres.setUploadedFileHash(s3FileManager.generateHash(pres.getUploadedFile()));
            String remoteFileName = pres.getUploadedFileHash() + ".tar.gz";
            Meeting meeting = ServiceUtils.findMeetingFromMeetingID(meetingId);
            if(meeting != null && meeting.isPresentationConversionCacheEnabled() && s3FileManager.exists(remoteFileName)) {
                S3Object s3Object = s3FileManager.download(remoteFileName);
                File parentDir = new File(pres.getUploadedFile().getParent());
                TarGzManager.decompress(s3Object, parentDir.getAbsolutePath());
                log.info("Presentation outputs restored from cache successfully for {}.", pres.getId());
            }
        } catch (Exception e) {
            log.error("Error while downloading presentations outputs from cache: {}", e.getMessage());
        }

        if (SupportedFileTypes.isPdfFile(pres.getFileType())) {
            boolean isNumberOfPagesOk = determineNumberOfPages(pres);
            if (!isNumberOfPagesOk) {
                return;
            }
        } else if (SupportedFileTypes.isImageFile(pres.getFileType())) {
            pres.setNumberOfPages(1);
        }

        long maxConversionTime = pres.getMaxTotalConversionTime();
        processUploadedPresentation(pres);

        DocConversionStarted started = new DocConversionStarted(pres.getPodId(), pres.getId(), pres.getName(),
                pres.getTemporaryPresentationId(), maxConversionTime, pres.getMeetingId(), pres.getAuthzToken());
        notifier.sendDocConversionProgress(started);

    }

    private void processMakePresentationDownloadableMsg(UploadedPresentation pres) {
        try {
            File presentationFileDir = pres.getUploadedFile().getParentFile();
            if (!pres.getFilenameConverted().equals("")) {
                String fileExtensionConverted = FilenameUtils.getExtension(pres.getFilenameConverted());
                Util.makePresentationDownloadable(presentationFileDir, pres.getId(), pres.isDownloadable(),
                        fileExtensionConverted);

            }
            String fileExtensionOriginal = FilenameUtils.getExtension(pres.getName());
            Util.makePresentationDownloadable(presentationFileDir, pres.getId(), pres.isDownloadable(),
                    fileExtensionOriginal);
        } catch (IOException e) {
            log.error("Failed to make presentation downloadable: {}", e);
        }
    }

    private void processUploadedPresentation(UploadedPresentation pres) {
        if (SupportedFileTypes.isPdfFile(pres.getFileType())) {
            pres.generateFilenameConverted("pdf");
            sendDocPageConversionStartedProgress(pres);
            PresentationConvertMessage msg = new PresentationConvertMessage(pres);
            presentationConversionCompletionService.handle(msg);
            executor.submit(() -> extractIntoPages(pres));
        } else if (SupportedFileTypes.isImageFile(pres.getFileType())) {
            sendDocPageConversionStartedProgress(pres);
            Future<?> future = executor.submit(() -> imageSlidesGenerationService.generateSlides(pres));

            supervisor.submit(monitorPresentationConversion(
                    future,
                    pres,
                    null,
                    pres.getMaxPageConversionTime()
            ));
        }
    }

    private Runnable monitorPresentationConversion(
            Future<?> future,
            UploadedPresentation pres,
            PageToConvert page,
            long timeout
    ) {
        return () -> {
            boolean createBlanks = false;

            try {
                future.get(timeout, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                log.error("Presentation conversion failed to execute: {}", e.getMessage());
                createBlanks = true;
            } catch (InterruptedException e) {
                log.error("Supervising thread interrupted: {}", e.getMessage());
            } catch (TimeoutException e) {
                log.error("Presentation conversion failed to convert in {} seconds", timeout);

                boolean success = future.cancel(true);
                if (!success) {
                    log.warn("Failed to cancel conversion task");
                }

                createBlanks = true;
            } catch (CancellationException e) {
                log.warn("Presentation conversion cancelled: {}", e.getMessage());
                createBlanks = true;
            }

            if (SupportedFileTypes.isPdfFile(pres.getFileType()) && page != null) {
                if (createBlanks) page.createBlanks();

                PageConvertProgressMessage msg = new PageConvertProgressMessage(
                        page.getPageNumber(),
                        page.getPresId(),
                        page.getMeetingId(),
                        new ArrayList<>()
                );

                pdfSlidesGenerationService.sendMessage(msg);
            } else if (SupportedFileTypes.isImageFile(pres.getFileType())) {
                if (createBlanks) imageSlidesGenerationService.createBlanks(pres);
                notifier.sendConversionUpdateMessage(1, pres, 1);
                notifier.sendConversionCompletedMessage(pres);
            }
        };
    }

    private void extractIntoPages(UploadedPresentation pres) {
        String presDir = pres.getUploadedFile().getParent();

        List<PageToConvert> listOfPagesConverted = new ArrayList<>();
        for (int page = 1; page <= pres.getNumberOfPages(); page++) {
            File pageFile = new File(presDir + "/page" + "-" + page + ".pdf");

            if(!pageFile.exists()) {
                File extractedPageFile = extractPage(pres, page);
                if (extractedPageFile.length() > maxBigPdfPageSize) {
                    File downscaledPageFile = downscalePage(pres, extractedPageFile, page);
                    downscaledPageFile.renameTo(pageFile);
                    extractedPageFile.delete();
                } else {
                    extractedPageFile.renameTo(pageFile);
                }
            }

            PageToConvert pageToConvert = new PageToConvert(
                    pres,
                    page,
                    pageFile,
                    svgImagesRequired,
                    generatePngs,
                    textFileCreator,
                    svgImageCreator,
                    thumbnailCreator,
                    pngCreator,
                    notifier
            );

            Future<?> future = pdfSlidesGenerationService.process(pageToConvert);

            supervisor.submit(monitorPresentationConversion(
                    future,
                    pres,
                    pageToConvert,
                    pres.getMaxPageConversionTime()
            ));

            listOfPagesConverted.add(pageToConvert);
            PageToConvert timeoutErrorMessage =
            listOfPagesConverted.stream().filter(item -> item.getMessageErrorInConversion() != null).findAny().orElse(null);

            if (timeoutErrorMessage != null) {
                log.error(timeoutErrorMessage.getMessageErrorInConversion());
                notifier.sendUploadFileTimedout(pres, timeoutErrorMessage.getPageNumber());
                break;
            }
        }
    }

    private File downscalePage(UploadedPresentation pres, File filePage, int pageNum) {
        String presDir = pres.getUploadedFile().getParent();
        File tempPage = new File(presDir + "/downscaled" + "-" + pageNum + ".pdf");
        PdfPageDownscaler downscaler = new PdfPageDownscaler();
        downscaler.downscale(filePage, tempPage);
        if (tempPage.exists()) {
            return tempPage;
        }

        return filePage;
    }

    private File extractPage(UploadedPresentation pres, int page) {
        String presDir = pres.getUploadedFile().getParent();

        File tempPage = new File(presDir + "/extracted" + "-" + page + ".pdf");
        pageExtractor.extractPage(pres.getUploadedFile(), tempPage, page);

        return tempPage;
    }

    private boolean determineNumberOfPages(UploadedPresentation pres) {
        try {
            Meeting meeting = ServiceUtils.findMeetingFromMeetingID(pres.getMeetingId());
            counterService.determineNumberOfPages(pres, meeting.getMaxNumPages());
            return true;
        } catch (CountingPageException e) {
            sendFailedToCountPageMessage(e, pres);
        }
        return false;
    }

    private void sendDocPageConversionStartedProgress(UploadedPresentation pres) {
        Map<String, Object> logData = new HashMap<String, Object>();

        logData.put("podId", pres.getPodId());
        logData.put("meetingId", pres.getMeetingId());
        logData.put("presId", pres.getId());
        logData.put("filename", pres.getName());
        logData.put("num_pages", pres.getNumberOfPages());
        logData.put("authzToken", pres.getAuthzToken());
        logData.put("logCode", "presentation_conversion_num_pages");
        logData.put("message", "Presentation conversion number of pages.");

        Gson gson = new Gson();
        String logStr = gson.toJson(logData);
        log.info(" --analytics-- data={}", logStr);

        DocPageConversionStarted progress = new DocPageConversionStarted(
                pres.getPodId(),
                pres.getMeetingId(),
                pres.getId(),
                pres.getName(),
                pres.getFilenameConverted(),
                pres.getAuthzToken(),
                pres.isDownloadable(),
                pres.isRemovable(),
                pres.isCurrent(),
                pres.isDefaultPresentation(),
                pres.getNumberOfPages());
        notifier.sendDocConversionProgress(progress);
    }

    private void sendFailedToCountPageMessage(CountingPageException e, UploadedPresentation pres) {
        ConversionUpdateMessage.MessageBuilder builder = new ConversionUpdateMessage.MessageBuilder(pres);

        if (e.getExceptionType() == CountingPageException.ExceptionType.PAGE_COUNT_EXCEPTION) {
            builder.messageKey(ConversionMessageConstants.PAGE_COUNT_FAILED_KEY);

            Map<String, Object> logData = new HashMap<>();
            logData.put("podId", pres.getPodId());
            logData.put("meetingId", pres.getMeetingId());
            logData.put("presId", pres.getId());
            logData.put("filename", pres.getName());
            logData.put("logCode", "determine_num_pages_failed");
            logData.put("message", "Failed to determine number of pages.");
            Gson gson = new Gson();
            String logStr = gson.toJson(logData);
            log.error(" --analytics-- data={}", logStr, e);

            DocPageCountFailed progress = new DocPageCountFailed(pres.getPodId(), pres.getMeetingId(),
                    pres.getId(), pres.getId(),
                    pres.getName(), "notUsedYet", "notUsedYet",
                    pres.isDownloadable(), pres.isRemovable(), ConversionMessageConstants.PAGE_COUNT_FAILED_KEY,
                    pres.getTemporaryPresentationId());

            notifier.sendDocConversionProgress(progress);

        } else if (e.getExceptionType() == CountingPageException.ExceptionType.PAGE_EXCEEDED_EXCEPTION) {
            builder.numberOfPages(e.getPageCount());
            builder.maxNumberPages(e.getMaxNumberOfPages());
            builder.messageKey(ConversionMessageConstants.PAGE_COUNT_EXCEEDED_KEY);

            Map<String, Object> logData = new HashMap<String, Object>();
            logData.put("podId", pres.getPodId());
            logData.put("meetingId", pres.getMeetingId());
            logData.put("presId", pres.getId());
            logData.put("filename", pres.getName());
            logData.put("pageCount", e.getPageCount());
            logData.put("maxNumPages", e.getMaxNumberOfPages());
            logData.put("logCode", "num_pages_exceeded");
            logData.put("message", "Number of pages exceeded.");
            Gson gson = new Gson();
            String logStr = gson.toJson(logData);
            log.warn(" --analytics-- data={}", logStr);

            DocPageCountExceeded progress = new DocPageCountExceeded(pres.getPodId(), pres.getMeetingId(),
                    pres.getId(), pres.getId(),
                    pres.getName(), "notUsedYet", "notUsedYet",
                    pres.isDownloadable(), pres.isRemovable(), ConversionMessageConstants.PAGE_COUNT_EXCEEDED_KEY,
                    e.getPageCount(), e.getMaxNumberOfPages(), pres.getTemporaryPresentationId());

            notifier.sendDocConversionProgress(progress);
        }
    }

    public void start() {
        log.info("Ready to process presentation files!");

        try {
            processPresentation = true;

            Runnable messageProcessor = new Runnable() {
                public void run() {
                    while (processPresentation) {
                        try {
                            UploadedPresentation pres = presentations.take();
                            processUploadedPresentation(pres);
                        } catch (InterruptedException e) {
                            log.warn("Error while taking presentation file from queue.");
                        }
                    }
                }
            };
            executor.submit(messageProcessor);
        } catch (Exception e) {
            log.error("Error processing presentation file:", e);
        }
    }

    public void stop() {
        processPresentation = false;
    }

    public void setSlidesGenerationProgressNotifier(SlidesGenerationProgressNotifier notifier) {
        this.notifier = notifier;
    }

    public void setCounterService(PageCounterService counterService) {
        this.counterService = counterService;
    }

    public void setPageExtractor(PageExtractor extractor) {
        this.pageExtractor = extractor;
    }

    public void setGeneratePngs(boolean generatePngs) {
        this.generatePngs = generatePngs;
    }

    public void setBigPdfSize(long bigPdfSize) {
        this.bigPdfSize = bigPdfSize;
    }

    public void setMaxBigPdfPageSize(long maxBigPdfPageSize) {
        this.maxBigPdfPageSize = maxBigPdfPageSize;
    }

    public void setSvgImagesRequired(boolean svgImagesRequired) {
        this.svgImagesRequired = svgImagesRequired;
    }

    public void setThumbnailCreator(ThumbnailCreator thumbnailCreator) {
        this.thumbnailCreator = thumbnailCreator;
    }

    public void setPngCreator(PngCreator pngCreator) {
        this.pngCreator = pngCreator;
    }

    public void setTextFileCreator(TextFileCreator textFileCreator) {
        this.textFileCreator = textFileCreator;
    }

    public void setSvgImageCreator(SvgImageCreator svgImageCreator) {
        this.svgImageCreator = svgImageCreator;
    }

    public void setImageSlidesGenerationService(ImageSlidesGenerationService s) {
        imageSlidesGenerationService = s;
    }

    public void setPresentationConversionCompletionService(PresentationConversionCompletionService s) {
        this.presentationConversionCompletionService = s;
    }

    public void setPdfSlidesGenerationService(PdfSlidesGenerationService s) {
        this.pdfSlidesGenerationService = s;
    }

    public void setS3FileManager(S3FileManager s3FileManager) {
        this.s3FileManager = s3FileManager;
    }
}
