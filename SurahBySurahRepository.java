package com.proggamoyquran.repository;

import android.app.Application;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.proggamoyquran.model.VerseAndWordCombineSurah;
import com.proggamoyquran.model.WordByWordSurahModel;
import com.proggamoyquran.model.WordToVerseModel;
import com.proggamoyquran.model.room.AyatAndWordDetails;
import com.proggamoyquran.model.room.AyatDetailsTableRow;
import com.proggamoyquran.model.room.WordDetailsTableRow;
import com.proggamoyquran.room.ProggamoyQuranDao;
import com.proggamoyquran.room.RoomClient;
import com.proggamoyquran.room.RoomDbHelper;
import com.proggamoyquran.util.Constants;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class SurahBySurahRepository {

    private final ProggamoyQuranDao proggamoyQuranDao;
    public final MutableLiveData<VerseAndWordCombineSurah> wordByWordSurahLiveData;

    public SurahBySurahRepository(Application application) {

        RoomDbHelper roomDbHelper = RoomClient.getDatabase(application);
        proggamoyQuranDao = roomDbHelper.proggamoyQuranDao();
        wordByWordSurahLiveData = new MutableLiveData<>();
    }

    public LiveData<VerseAndWordCombineSurah> getWordByWordSurahLiveData() {
        return wordByWordSurahLiveData;
    }

    public void getAyatAndWordDetailsBySurah(int surahNo) {

        Log.e("AYAT", "Outside | ExecutorService");
        int numberOfThreads = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);

        List<Future<List<AyatAndWordDetails>>> futures = new ArrayList<>();

        for (int i = 0; i < numberOfThreads; i++) {
            Future<List<AyatAndWordDetails>> future = executor.submit(() ->
                    proggamoyQuranDao.getAyatAndWordDetailsBySurah(surahNo));
            futures.add(future);
        }


        List<AyatAndWordDetails> mergedList = new ArrayList<>();
        for (Future<List<AyatAndWordDetails>> future : futures) {
            try {
                mergedList.addAll(future.get());

            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }

        Log.e("AYAT", "ExecutorService | Last Size: " + mergedList.size());

        // Shutdown the executor when done
        executor.shutdown();
    }

    public void getAyatAndWordDetailsBySurahInThread(int surahNo) {

        Log.e("AYAT", "Outside | Thread");
        int noOfThreads = Runtime.getRuntime().availableProcessors();
        List<AyatAndWordDetails>[] results = new List[noOfThreads];
        Thread[] threads = new Thread[noOfThreads];

        for (int i = 0; i < noOfThreads; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                results[index] = proggamoyQuranDao.getAyatAndWordDetailsBySurah(surahNo);
            });
            threads[i].start();
        }

        try {
            for (Thread thread : threads) {
                thread.join();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        List<AyatAndWordDetails> ayatAndWordDetails = new ArrayList<>();
        for (List<AyatAndWordDetails> result : results) {
            ayatAndWordDetails.addAll(result);
        }
        Log.e("AYAT", "Thread | Last Size: " + ayatAndWordDetails.size());
    }

    public void wordByWordMultiThreadOptimized(int translationIndex, int surahNo) {

        int numberOfThreads = Runtime.getRuntime().availableProcessors();
        ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);
        List<Future<VerseAndWordCombineSurah>> futureList = new ArrayList<>();

        // AyatDetails from local database
        List<AyatDetailsTableRow> ayatListBySurah = proggamoyQuranDao.getAyatBySurah(surahNo);
        // Log.e("AYAT", "Start | " + ayatListBySurah.size());

        int batchSize = ayatListBySurah.size() / numberOfThreads;

        for (int i = 0; i < numberOfThreads; i++) {

            int startIndex = i * batchSize;
            int endIndex = (i == numberOfThreads - 1) ? ayatListBySurah.size() : (i + 1) * batchSize;

            List<AyatDetailsTableRow> ayatDetailsSubList = ayatListBySurah.subList(startIndex, endIndex);

            Future<VerseAndWordCombineSurah> future = executorService.submit(() ->
                    processVerseAndWordCombineSurahBatch(ayatDetailsSubList, translationIndex, surahNo));

            futureList.add(future);
        }
        executorService.shutdown();

        try {
            if (!executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {
                Log.d("ExecutorService", "ExecutorService did not terminate within the specified timeout.");
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        List<WordByWordSurahModel> wordByWordDetailsBySurah = new ArrayList<>();
        List<WordToVerseModel> ayatList = new ArrayList<>();

        for (Future<VerseAndWordCombineSurah> combineSurahFuture : futureList) {
            try {
                wordByWordDetailsBySurah.addAll(combineSurahFuture.get().getWordByWordSurahModels());
                ayatList.addAll(combineSurahFuture.get().getWordToVerseList());
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }
        // Log.e("AYAT", "End | " + ayatListBySurah.size());
        wordByWordSurahLiveData.postValue(new VerseAndWordCombineSurah(wordByWordDetailsBySurah, ayatList));
    }

    private VerseAndWordCombineSurah processVerseAndWordCombineSurahBatch(List<AyatDetailsTableRow> ayatDetailsList, int translationIndex, int surahNo) {

        List<WordToVerseModel> ayatList = new ArrayList<>();
        List<WordByWordSurahModel> wordByWordDetailsBySurah = new ArrayList<>();

        for (AyatDetailsTableRow ayatDetails : ayatDetailsList) {

            int ayatNo = ayatDetails.getAyatNo();
            int indexAyat = ayatDetails.getAyatId();
            List<Integer> wordNumbers = new ArrayList<>();
            List<String> wordAndMeaningList = new ArrayList<>();
            List<String> moreInfoList = new ArrayList<>();
            List<String> rootWordList = new ArrayList<>();
            List<String> grammarList = new ArrayList<>();
            List<String> lemmaList = new ArrayList<>();
            List<String> wordAudioList = new ArrayList<>();
            String ayatMeaning = (translationIndex == Constants.BENGALI_TRANSLATION) ? ayatDetails.getBanglaMeaning() : ayatDetails.getEnglishMeaning();

            List<WordDetailsTableRow> wordListByAyat = proggamoyQuranDao.getWordDetailsListByAyat(surahNo, ayatNo);

            for (WordDetailsTableRow wordDetails : wordListByAyat) {

                wordNumbers.add(wordDetails.getWordNo());
                wordAndMeaningList.add(wordDetails.getWord() + "\n" + wordDetails.getWordBnMeaning()); // Concatenate arabic word and word meaning
                moreInfoList.add("");
                rootWordList.add(wordDetails.getRootWord());
                grammarList.add(wordDetails.getGrammar());
                lemmaList.add(wordDetails.getLemma());
                wordAudioList.add(""); // TODO Audio url
            }
            wordByWordDetailsBySurah.add(new WordByWordSurahModel(surahNo, ayatNo, indexAyat,
                    wordNumbers, wordAndMeaningList, moreInfoList, rootWordList, grammarList,
                    lemmaList, wordAudioList, ayatMeaning));

            ayatList.add(new WordToVerseModel(ayatNo, indexAyat, wordAndMeaningList, ayatMeaning));
        }
        return new VerseAndWordCombineSurah(wordByWordDetailsBySurah, ayatList);
    }

    /**
     * Using multi-thread, Surah Al Baqara all word ayat (286) process in 6 seconds
     */
    public void wordByWordMultiThread(int translationIndex, int surahNo) {

        int numberOfThreads = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        List<Future<List<WordByWordSurahModel>>> futures = new ArrayList<>();
        List<Future<List<WordToVerseModel>>> ayatListFutures = new ArrayList<>();

        // AyatDetails from local database
        List<AyatDetailsTableRow> ayatListBySurah = proggamoyQuranDao.getAyatBySurah(surahNo);
        Log.e("AYAT", "Start | " + ayatListBySurah.size());

        int batchSize = ayatListBySurah.size() / numberOfThreads;

        for (int i = 0; i < numberOfThreads; i++) {
            int startIndex = i * batchSize;
            int endIndex = (i == numberOfThreads - 1) ? ayatListBySurah.size() : (i + 1) * batchSize;

            List<AyatDetailsTableRow> ayatDetailsSubList = ayatListBySurah.subList(startIndex, endIndex);

            Future<List<WordByWordSurahModel>> future = executor.submit(() -> processAyatDetailsBatch(ayatDetailsSubList, translationIndex, surahNo));
            Future<List<WordToVerseModel>> ayatListFuture = executor.submit(() -> processWordToVerseBatch(ayatDetailsSubList, translationIndex, surahNo));
            futures.add(future);
            ayatListFutures.add(ayatListFuture);
        }
        executor.shutdown();

        try {
            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            // Handle exception
            e.printStackTrace();
        }

        List<WordByWordSurahModel> wordByWordDetailsBySurah = new ArrayList<>();
        List<WordToVerseModel> ayatList = new ArrayList<>();

        for (Future<List<WordByWordSurahModel>> future : futures) {
            try {
                wordByWordDetailsBySurah.addAll(future.get());
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }

        for (Future<List<WordToVerseModel>> future : ayatListFutures) {
            try {
                ayatList.addAll(future.get());
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }
        Log.e("AYAT", "End | " + ayatListBySurah.size());
        wordByWordSurahLiveData.postValue(new VerseAndWordCombineSurah(wordByWordDetailsBySurah, ayatList));
    }

    private List<WordByWordSurahModel> processAyatDetailsBatch(List<AyatDetailsTableRow> ayatDetailsList, int translationIndex, int surahNo) {
        List<WordByWordSurahModel> wordByWordDetailsBySurahBatch = new ArrayList<>();

        for (AyatDetailsTableRow ayatDetails : ayatDetailsList) {

            int ayatNo = ayatDetails.getAyatNo();
            int indexAyat = ayatDetails.getAyatId();
            List<Integer> wordNumbers = new ArrayList<>();
            List<String> wordAndMeaningList = new ArrayList<>();
            List<String> moreInfoList = new ArrayList<>();
            List<String> rootWordList = new ArrayList<>();
            List<String> grammarList = new ArrayList<>();
            List<String> lemmaList = new ArrayList<>();
            List<String> wordAudioList = new ArrayList<>();
            String ayatMeaning = (translationIndex == Constants.BENGALI_TRANSLATION) ? ayatDetails.getBanglaMeaning() : ayatDetails.getEnglishMeaning();

            List<WordDetailsTableRow> wordListByAyat = proggamoyQuranDao.getWordDetailsListByAyat(surahNo, ayatNo);

            for (WordDetailsTableRow wordDetails : wordListByAyat) {

                wordNumbers.add(wordDetails.getWordNo());
                wordAndMeaningList.add(wordDetails.getWord() + "\n" + wordDetails.getWordBnMeaning()); // Concatenate arabic word and word meaning
                moreInfoList.add("");
                rootWordList.add(wordDetails.getRootWord());
                grammarList.add(wordDetails.getGrammar());
                lemmaList.add(wordDetails.getLemma());
                wordAudioList.add(""); // TODO Audio url
            }
            wordByWordDetailsBySurahBatch.add(new WordByWordSurahModel(surahNo, ayatNo, indexAyat,
                    wordNumbers, wordAndMeaningList, moreInfoList, rootWordList, grammarList,
                    lemmaList, wordAudioList, ayatMeaning));
        }
        return wordByWordDetailsBySurahBatch;
    }

    private List<WordToVerseModel> processWordToVerseBatch(List<AyatDetailsTableRow> ayatDetailsList, int translationIndex, int surahNo) {
        List<WordToVerseModel> wordToVerseBatchList = new ArrayList<>();

        for (AyatDetailsTableRow ayatDetails : ayatDetailsList) {

            List<String> wordAndMeaningList = new ArrayList<>();
            int ayatNo = ayatDetails.getAyatNo();
            String ayatMeaning = (translationIndex == Constants.BENGALI_TRANSLATION) ? ayatDetails.getBanglaMeaning() : ayatDetails.getEnglishMeaning();

            List<WordDetailsTableRow> wordListByAyat = proggamoyQuranDao.getWordDetailsListByAyat(surahNo, ayatNo);

            for (WordDetailsTableRow wordDetails : wordListByAyat) {

                wordAndMeaningList.add(wordDetails.getWord() + "\n" + wordDetails.getWordBnMeaning()); // Concatenate arabic word and word meaning
            }
            wordToVerseBatchList.add(new WordToVerseModel(ayatNo, ayatDetails.getAyatId(), wordAndMeaningList, ayatMeaning));
        }
        return wordToVerseBatchList;
    }

    /**
     * wordByWordLocalJsonFormat work fine but the data processing need many times.
     * Using single-thread, Surah Al Baqara all word ayat (286) process in 14 seconds.
     */
    public void wordByWordLocalJsonFormat(int translationIndex, int surahNo) {

        List<AyatDetailsTableRow> ayatListBySurah = proggamoyQuranDao.getAyatBySurah(surahNo);

        Log.e("AYAT", "Start | " + ayatListBySurah.size());

        List<WordToVerseModel> ayatList = new ArrayList<>();
        List<WordByWordSurahModel> wordByWordDetailsBySurah = new ArrayList<>();

        for (AyatDetailsTableRow ayatDetails : ayatListBySurah) {

            int ayatNo = ayatDetails.getAyatNo();
            int indexAyat = ayatDetails.getAyatId();
            List<Integer> wordNumbers = new ArrayList<>();
            List<String> wordAndMeaningList = new ArrayList<>();
            List<String> moreInfoList = new ArrayList<>();
            List<String> rootWordList = new ArrayList<>();
            List<String> grammarList = new ArrayList<>();
            List<String> lemmaList = new ArrayList<>();
            List<String> wordAudioList = new ArrayList<>();
            String ayatMeaning = (translationIndex == Constants.BENGALI_TRANSLATION) ? ayatDetails.getBanglaMeaning() : ayatDetails.getEnglishMeaning();

            List<WordDetailsTableRow> wordListByAyat = proggamoyQuranDao.getWordDetailsListByAyat(surahNo, ayatNo);

            for (WordDetailsTableRow wordDetails : wordListByAyat) {

                wordNumbers.add(wordDetails.getWordNo());
                wordAndMeaningList.add(wordDetails.getWord() + "\n" + wordDetails.getWordBnMeaning()); // Concatenate arabic word and word meaning
                moreInfoList.add("");
                rootWordList.add(wordDetails.getRootWord());
                grammarList.add(wordDetails.getGrammar());
                lemmaList.add(wordDetails.getLemma());
                wordAudioList.add(""); // TODO Audio url
            }
            wordByWordDetailsBySurah.add(new WordByWordSurahModel(surahNo, ayatNo, indexAyat,
                    wordNumbers, wordAndMeaningList, moreInfoList, rootWordList, grammarList,
                    lemmaList, wordAudioList, ayatMeaning));

            ayatList.add(new WordToVerseModel(ayatNo, indexAyat, wordAndMeaningList, ayatMeaning));
        }
        Log.e("AYAT", "End | " + ayatListBySurah.size());
        wordByWordSurahLiveData.postValue(new VerseAndWordCombineSurah(wordByWordDetailsBySurah, ayatList));
    }

    /* Thread and ExecutorService example */
    /* // Thread
        Log.e("AYAT", "Outsize Thread");
        new Thread(() -> {

            List<AyatAndWordDetails> ayatAndWordDetails = proggamoyQuranDao.getAyatAndWordDetailsBySurah(surahNo);
            Log.e("AYAT", "Inside Thread - Size: " + ayatAndWordDetails.size());

        }).start();

        // ExecutorService
        int noOfThread = Runtime.getRuntime().availableProcessors();
        Log.e("AYAT", "Outsize ExecutorService" + noOfThread);

        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        executor.execute(() -> {
            List<AyatAndWordDetails> ayatAndWordDetails = proggamoyQuranDao.getAyatAndWordDetailsBySurah(surahNo);
            Log.e("AYAT", "Inside ExecutorService - Size: " + ayatAndWordDetails.size());
        });
        executor.shutdown();

        new Thread(() -> {
            List<AyatAndWordDetails> ayatAndWordDetails = proggamoyQuranDao.getAyatAndWordDetailsBySurah(surahNo);

            // Post the result back to the UI thread
            new Handler(Looper.getMainLooper()).post(() -> {
                Log.e("AYAT", "Inside - Size: " + ayatAndWordDetails.size());
                // Update the UI with the fetched data
            });
        }).start();*/
}
