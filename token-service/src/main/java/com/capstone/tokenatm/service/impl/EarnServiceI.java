package com.capstone.tokenatm.service.impl;

import com.capstone.tokenatm.entity.SpendLogEntity;
import com.capstone.tokenatm.entity.TokenCountEntity;
import com.capstone.tokenatm.exceptions.BadRequestException;
import com.capstone.tokenatm.exceptions.InternalServerException;
import com.capstone.tokenatm.service.*;
import com.capstone.tokenatm.service.Beans.Assignment;
import com.capstone.tokenatm.service.Beans.AssignmentStatus;
import com.capstone.tokenatm.service.Beans.Student;
import com.capstone.tokenatm.service.Response.UpdateTokenResponse;
import com.capstone.tokenatm.service.Response.UseTokenResponse;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.configurationprocessor.json.JSONArray;
import org.springframework.boot.configurationprocessor.json.JSONException;
import org.springframework.boot.configurationprocessor.json.JSONObject;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URL;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service("EarnService")
public class EarnServiceI implements EarnService {

    //Canvas API settings
    //TODO: The API Endpoint and Bearer token is only used for testing. Please change to UCI endpoint and actual tokens in prod
    //Bearer Token for dummy canvas endpoint
    private static final String BEARER_TOKEN = "7~sKb3Kq7M9EjSgDtMhugxCEs5oD76pbJgBWAFScBliSi7Iin8QubiBHEBlrWfYunG";
    //Testing endpoint for Canvas
    private static final String CANVAS_API_ENDPOINT = "https://canvas.instructure.com/api/v1";
    //Course Id
    private static final int COURSE_ID = 3737737;

    //Qualtrics API Settings
    //TODO: The API Endpoint and API key is only used for testing. Please change to UCI endpoint and actual keys in prod
    //API Key for Qualtrics
    private static final String API_KEY = "3yoP4lV2G7wmxOVtIkH6G8K5IcGDgtdUf2Ys3um9";
    //Testing endpoint for Qualtrics
    private static final String QUALTRICS_API_ENDPOINT = "https://iad1.qualtrics.com/API/v3";

    private static final String QualtricsBody = "{\"format\":\"json\",\"compress\":\"false\"}";
    public static final MediaType JSON
            = MediaType.get("application/json; charset=utf-8");

    public static final int PER_PAGE = 100;

    private static final Logger LOGGER = LoggerFactory.getLogger(EarnService.class);


    //List of Quizzes in the first module (which needs over 70% average to earn the initial 2 tokens)
    private static List<String> tokenQuizIds = Arrays.asList("12427623", "12476618", "12476695");

    //Map of assignments that are can be resubmitted
    private static Map<String, String> resubmissionsMap = new HashMap<>();
    static {
        resubmissionsMap.put("33741790", "33811609");
        resubmissionsMap.put("33741750", "33811823");
        resubmissionsMap.put("33741783", "33811829");
    }

    //List of surveys
    private static List<String> tokenSurveyIds = Arrays.asList("SV_8oIf0qAz5g0TFiK");

    @Autowired
    private TokenRepository tokenRepository;

    @Autowired
    private LogRepository logRepository;

    //Token earning deadlines
    private static final List<Date> survey_deadlines = new ArrayList<>();
    private static Date module_deadline;

    static {
        //Set deadlines for surveys
        List<int[]> deadline_time_list = Arrays.asList(
                new int[]{2022, 10, 14, 23, 45}
        );
        for (int[] deadline : deadline_time_list) {
            Calendar calendar = Calendar.getInstance();
            calendar.set(deadline[0], deadline[1], deadline[2], deadline[3], deadline[4]);
            survey_deadlines.add(calendar.getTime());
        }

        //Set deadline for Module 1
        Calendar module_cal = Calendar.getInstance();
        module_cal.set(2022, 9, 26);
        module_deadline = module_cal.getTime();
    }

    /**
     * Fetch grades of all quizzes that is required to earn tokens
     *
     * @return Map of grades, key is student id, value is grade of that student for this assignment
     * @throws IOException
     * @throws JSONException
     */
    @Override
    public Map<String, Double> getStudentTokenGrades() throws IOException, JSONException {
        Map<String, Double> averageQuizScores = new HashMap<>();
        for (String quizId : tokenQuizIds) {
            Map<String, Double> quizScores = getStudentQuizScores(quizId);
            quizScores.entrySet().forEach(e -> {
                String userId = e.getKey();
                averageQuizScores.put(userId, averageQuizScores.getOrDefault(userId, 0.0) + e.getValue());
            });
        }
        averageQuizScores.entrySet().forEach(e -> e.setValue(e.getValue() / tokenQuizIds.size()));
        return averageQuizScores;
    }

    public void init() throws JSONException, IOException {
        tokenRepository.deleteAll();
        logRepository.deleteAll();
        Map<String, Student> studentMap = getStudents();
        studentMap.entrySet().stream().forEach(e -> {
            Student student = e.getValue();
            TokenCountEntity entity = getEntityFromStudent(student);
            entity.setToken_count(0);
            tokenRepository.save(entity);
        });
    }

    private TokenCountEntity getEntityFromStudent(Student student) {
        TokenCountEntity entity = new TokenCountEntity();
        entity.setUser_id(student.getId());
        entity.setUser_name(student.getName());
        entity.setUser_email(student.getEmail());
        entity.setTimestamp(new Date());
        return entity;
    }

    private void updateTokenEntity(Map<String, Student> studentMap, String user_id, int add_count, String source) {
        Student student = studentMap.getOrDefault(user_id, null);
        if (student == null) {
            LOGGER.error("Error: Student " + user_id + " does not exist in enrollment list");
            return;
        }
        Optional<TokenCountEntity> optional = tokenRepository.findById(user_id);
        TokenCountEntity entity = null;
        if (optional.isPresent()) {
            entity = optional.get();
            entity.setToken_count(entity.getToken_count() + add_count);
        } else {
            entity = getEntityFromStudent(student);
            entity.setToken_count(add_count);
        }
        tokenRepository.save(entity);

        //Generate token use log
        logRepository.save(createLog(user_id, student.getName(), add_count >= 0 ? "earn" : "spend", add_count, source));
    }

    private SpendLogEntity createLog(String user_id, String user_name, String type, Integer token_count, String source) {
        SpendLogEntity n = new SpendLogEntity();
        n.setUser_id(user_id);
        n.setUser_name(user_name);
        n.setType(type);
        n.setTokenCount(token_count);
        n.setSourcee(source);
        n.setTimestamp(new Date());
        return n;
    }

    public Iterable<TokenCountEntity> manualSyncTokens() throws JSONException, IOException {
        Iterable<SpendLogEntity> originalLogs = logRepository.findAll();
        init();
        syncModule();
        for (String surveyId : tokenSurveyIds) {
            syncSurvey(surveyId);
        }
        syncLog(originalLogs);
        return tokenRepository.findAll();
    }

    private void syncSurvey(String surveyId) {
        System.out.println("Fetching Qualtrics Survey " + surveyId);
        Map<String, Student> studentMap = null;
        Set<String> usersToUpdate = new HashSet<>();//List of user_ids that should +1 token
        Set<String> completed_emails = new HashSet<>();
        try {
            completed_emails = getSurveyCompletions(surveyId);
            studentMap = getStudents();
        } catch (InternalServerException | IOException | JSONException e) {
            e.printStackTrace();
        }
        completed_emails.add("canapitest+4@gmail.com"); // fake_data
        completed_emails.add("canapitest+5@gmail.com"); // fake_data
        completed_emails.add("canapitest+6@gmail.com"); // fake_data
        completed_emails.add("canapitest+7@gmail.com"); // fake_data
        for (Map.Entry<String, Student> entry : studentMap.entrySet()) {
            Student student = entry.getValue();
            if (completed_emails.contains(student.getEmail())) {
                usersToUpdate.add(student.getId());
            }
        }

        for (String userId : usersToUpdate) {
            updateTokenEntity(studentMap, userId, 1, "Qualtrics Survey: " + surveyId);
        }
    }

    private void syncModule() {
        Map<String, Double> quizGrades = null;
        Map<String, Student> studentMap = null;
        Set<String> usersToUpdate = new HashSet<>();//List of user_ids that should +2 tokens
        System.out.println("Running Module 1");
        try {
            quizGrades = getStudentTokenGrades();
            studentMap = getStudents();
        } catch (IOException | JSONException e) {
            e.printStackTrace();
        }

        if (quizGrades != null && studentMap != null) {
            for (Map.Entry<String, Double> entry : quizGrades.entrySet()) {
                String user_id = String.valueOf(entry.getKey());
                Double quiz_aver = Double.valueOf(entry.getValue());
                if (quiz_aver >= 70.00) {
                    usersToUpdate.add(user_id);
                }
            }

            for (String user_id : usersToUpdate) {
                updateTokenEntity(studentMap, user_id, 2, "Module 1");
            }
        }
    }

    private void syncLog(Iterable<SpendLogEntity> logs) {
        try {
            Map<String, Student> studentMap = getStudents();
            for (SpendLogEntity log : logs) {
                String user_id = String.valueOf(log.getUserId());
                Integer token_count = log.getTokenCount();
                if (log.getType().equals("spend")) {
                    System.out.println(user_id + " spend " + token_count + " tokens");
                    updateTokenEntity(studentMap, user_id, -token_count, log.getSource());
                }
            }
        } catch (IOException | JSONException e) {
            e.printStackTrace();
        }
    }

    @Async
    @Override
    public void syncTokensOnDeadline() throws JSONException, IOException {
        init();
        ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
        TaskScheduler scheduler = new ConcurrentTaskScheduler(executorService);

        //Schedule Module 1
        scheduler.schedule(() -> syncModule(), module_deadline);

        for (int i = 0; i < tokenSurveyIds.size(); i++) {
            String surveyId = tokenSurveyIds.get(i);
            Date deadline = survey_deadlines.get(i);
            scheduler.schedule(() -> syncSurvey(surveyId), deadline);
        }
    }

    @Override
    public Iterable<TokenCountEntity> getAllStudentTokenCounts() {
        return tokenRepository.findAll();
    }

    @Override
    public Optional<TokenCountEntity> getStudentTokenCount(String user_id) {
        return tokenRepository.findById(user_id);
    }

    @Override
    public String getIdentity() throws IOException {
        URL url = UriComponentsBuilder
                .fromUriString(QUALTRICS_API_ENDPOINT + "/whoami")
                .build().toUri().toURL();
        String response = apiProcess(url, false);
        return response;
    }

    private String apiProcess(URL url, Boolean isCanvas) throws IOException {
        return apiProcess(url, "", isCanvas);
    }

    private String apiProcess(URL url, String body, Boolean isCanvas) throws IOException {
        OkHttpClient client = new OkHttpClient();
        Request.Builder builder = new Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json");
        if (isCanvas) {
            builder.addHeader("Authorization", "Bearer " + BEARER_TOKEN);
        } else {
            builder.addHeader("X-API-TOKEN", API_KEY);
        }
        if (body.length() > 0) {
            builder.post(RequestBody.create(body, JSON));
        }
        Request request = builder.build();
        try (Response response = client.newCall(request).execute()) {
            return response.body().string();
        }
    }
    private Integer apiProcess(URL url, RequestBody body, Boolean isCanvas) throws IOException {
        OkHttpClient client = new OkHttpClient();
        Request.Builder builder = new Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json");
        if (isCanvas) {
            builder.addHeader("Authorization", "Bearer " + BEARER_TOKEN);
        } else {
            builder.addHeader("X-API-TOKEN", API_KEY);
        }
        builder.method("POST",body);
        Request request = builder.build();
        try (Response response = client.newCall(request).execute()) {
            return response.code();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 400;
    }

    private Map<String, Student> getStudents() throws IOException, JSONException {
        int page = 1;
        Map<String, Student> studentMap = new HashMap<>();
        while (true) {
            URL url = UriComponentsBuilder
                    .fromUriString(CANVAS_API_ENDPOINT + "/courses/" + COURSE_ID + "/users")
                    .queryParam("page", page)
                    .queryParam("per_page", PER_PAGE)
                    .build().toUri().toURL();

            String response = apiProcess(url, true);
            JSONArray result = new JSONArray(response);
            for (int i = 0; i < result.length(); i++) {
                String id = ((JSONObject) result.get(i)).get("id").toString();
                String name = ((JSONObject) result.get(i)).get("name").toString();
                String email = ((JSONObject) result.get(i)).get("email").toString();
                studentMap.put(id, new Student(id, name, email));
            }
            if (result.length() < PER_PAGE)
                break;
            page++;
        }
        return studentMap;
    }

    @Override
    public HashMap<Object, Object> getStudentGrades() throws IOException, JSONException {
        Map<String, Student> students = getStudents();
        String users_id = students.entrySet().stream().map(e -> "&student_ids%5B%5D=" + e.getValue().getId()).collect(Collectors.joining(""));
        int page = 1;
        HashMap<Object, Object> students_data = new HashMap<>();

        while (true) {
            URL url = new URL(CANVAS_API_ENDPOINT + "/courses/" + COURSE_ID + "/students/submissions?exclude_response_fields%5B%5D=preview_url&grouped=1&response_fields%5B%5D=assignment_id&response_fields%5B%5D=attachments&response_fields%5B%5D=attempt&response_fields%5B%5D=cached_due_date&response_fields%5B%5D=entered_grade&response_fields%5B%5D=entered_score&response_fields%5B%5D=excused&response_fields%5B%5D=grade&response_fields%5B%5D=grade_matches_current_submission&response_fields%5B%5D=grading_period_id&response_fields%5B%5D=id&response_fields%5B%5D=late&response_fields%5B%5D=late_policy_status&response_fields%5B%5D=missing&response_fields%5B%5D=points_deducted&response_fields%5B%5D=posted_at&response_fields%5B%5D=redo_request&response_fields%5B%5D=score&response_fields%5B%5D=seconds_late&response_fields%5B%5D=submission_type&response_fields%5B%5D=submitted_at&response_fields%5B%5D=url&response_fields%5B%5D=user_id&response_fields%5B%5D=workflow_state&student_ids%5B%5D="
                    + users_id + "&page=" + page + "&per_page=" + PER_PAGE);
            String response = apiProcess(url, true);
            JSONArray result = new JSONArray(response);

            for (int i = 0; i < result.length(); i++) {
                ArrayList<String> grades = new ArrayList<>();
                for (int j = 0; j < ((JSONArray) ((JSONObject) result.get(i)).get("submissions")).length(); j++) {
                    String assignment_id = ((JSONObject) ((JSONArray) ((JSONObject) result.get(i)).get("submissions")).get(j)).get("assignment_id").toString();
                    String score = ((JSONObject) ((JSONArray) ((JSONObject) result.get(i)).get("submissions")).get(j)).get("score").toString();
                    grades.add(score + "(" + assignment_id + ")");
                }
                String user_id = ((JSONObject) result.get(i)).get("user_id").toString();
                students_data.put("(" + user_id + ")", grades);
            }
            if (result.length() < PER_PAGE)
                break;
            page++;
        }
        return students_data;
    }


    @Override
    public Map<String, Object> getCourseData() throws IOException, JSONException {
        int page = 1;
        Map<String, Object> result = new HashMap<>();
        ArrayList<HashMap<Object, Object>> course_data = new ArrayList<>();
        while (true) {
            URL url = new URL(CANVAS_API_ENDPOINT + "/courses/" + COURSE_ID + "/assignment_groups?exclude_assignment_submission_types%5B%5D=wiki_page&exclude_response_fields%5B%5D=description&exclude_response_fields%5B%5D=in_closed_grading_period&exclude_response_fields%5B%5D=needs_grading_count&exclude_response_fields%5B%5D=rubric&include%5B%5D=assignment_group_id&include%5B%5D=assignment_visibility&include%5B%5D=assignments&include%5B%5D=grades_published&include%5B%5D=post_manually&include%5B%5D=module_ids&override_assignment_dates=false"
                    + "&page=" + page + "&per_page=" + PER_PAGE);
            JSONArray response = new JSONArray(apiProcess(url, true));
            for (int i = 0; i < response.length(); i++) {
                for (int j = 0; j < ((JSONArray) ((JSONObject) response.get(i)).get("assignments")).length(); j++) {
                    HashMap<Object, Object> item = new HashMap<>();
                    String assignment_id = ((JSONObject) ((JSONArray) ((JSONObject) response.get(i)).get("assignments")).get(j)).get("id").toString();
                    String assignment_name = ((JSONObject) ((JSONArray) ((JSONObject) response.get(i)).get("assignments")).get(j)).get("name").toString();
                    item.put("assignment_id", assignment_id);
                    item.put("assignment_name", assignment_name);
                    course_data.add(item);
                }
            }
            if (response.length() < PER_PAGE)
                break;
            page++;
        }
        result.put("result", course_data);
        return result;
    }

    /**
     * Fetch completion status of required surveys
     * See https://api.qualtrics.com/6b00592b9c013-start-response-export for details of API
     *
     * @return
     * @throws IOException
     * @throws JSONException
     * @throws BadRequestException
     */
    @Override
    public Set<String> getSurveyCompletions(String surveyId) throws InternalServerException {
        try {
            URL url = UriComponentsBuilder
                    .fromUriString(QUALTRICS_API_ENDPOINT + "/surveys/" + surveyId + "/export-responses")
                    .build().toUri().toURL();
            String response = apiProcess(url, QualtricsBody, false);
            JSONObject resultObj = new JSONObject(response).getJSONObject("result");
            String progressId = resultObj.getString("progressId");
            ExportResponse exportResponse = null;
            while (true) {
                exportResponse = getExportStatus(surveyId, progressId);
                LOGGER.info("Current status: " + exportResponse.status + ", Progress: " + exportResponse.getPercentComplete());
                if (exportResponse.getStatus().equals("complete")) {
                    //export success
                    return getSurveyCompletedEmailAddresses(surveyId, exportResponse.getFileId());
                } else if (exportResponse.getStatus().equals("failed")) {
                    //export failed
                    LOGGER.error("Failed to download survey export, progress = " + exportResponse.getPercentComplete() + "%");
                    throw new InternalServerException("Download of survey export failed");
                } else {
                    //still in progress
                    LOGGER.info("Download in progress, current completed: " + exportResponse.getPercentComplete() + "%");
                }
            }
        } catch (IOException | JSONException e) {
            e.printStackTrace();
            throw new InternalServerException("Error when processing survey data");
        }

    }

    /**
     * Fetch the current exporting progress of given progressId, should iterate until percentage is 100.0
     *
     * @param progressId
     * @return
     * @throws IOException
     * @throws JSONException
     */
    private ExportResponse getExportStatus(String surveyId, String progressId) throws IOException, JSONException {
        URL url = UriComponentsBuilder
                .fromUriString(QUALTRICS_API_ENDPOINT + "/surveys/" + surveyId + "/export-responses/" + progressId)
                .build().toUri().toURL();
        String response = apiProcess(url, false);
        //A more elegant way is to use the ObjectMapper, but initializing it is very costly
        JSONObject resultObj = new JSONObject(response).getJSONObject("result");
        return new ExportResponse(
                resultObj.getString("fileId"),
                resultObj.getDouble("percentComplete"),
                resultObj.getString("status"));
    }

    private Set<String> getSurveyCompletedEmailAddresses(String surveyId, String fileId) throws IOException, JSONException {
        LOGGER.info("FileId = " + fileId);
        Set<String> completedEmails = new HashSet<>();
        URL url = UriComponentsBuilder
                .fromUriString(QUALTRICS_API_ENDPOINT + "/surveys/" + surveyId + "/export-responses/" + fileId + "/file")
                .build().toUri().toURL();
        String response = apiProcess(url, false);
        JSONArray responseList = new JSONObject(response).getJSONArray("responses");
        for (int i = 0; i < responseList.length(); i++) {
            JSONObject responseItem = responseList.getJSONObject(i).getJSONObject("values");
            String emailAddress = responseItem.getString("EmailAddress");
            completedEmails.add(emailAddress);
        }
        return completedEmails;
    }

    //This is the version of updating the original assignment, currently not under use
//    public UseTokenResponse useToken_OriginalAssignment(String user_id, String assignment_id, Integer cost) throws IOException, BadRequestException, JSONException {
//        Optional<TokenCountEntity> optional = tokenRepository.findById(user_id);
//        if (!optional.isPresent()) {
//            LOGGER.error("Error: Student " + user_id + " is not in current database");
//            throw new BadRequestException("Student " + user_id + " is not in current database");
//        }
//        TokenCountEntity entity = optional.get();
//        Integer token_amount = entity.getToken_count();
//        if (token_amount >= cost) {
//            Date current_time = new Date();
//            token_amount = token_amount - cost;
//            String title = "Resubmission";
//            Date due =  new Date(current_time.getTime() + 24*60*60*1000);
//
//            URL url = UriComponentsBuilder
//                    .fromUriString(CANVAS_API_ENDPOINT + "/courses/" + COURSE_ID + "/assignments/" + assignment_id + "/overrides")
//                    .build().toUri().toURL();
//            RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
//                                                          .addFormDataPart("assignment_override[student_ids][]",user_id)
//                                                          .addFormDataPart("assignment_override[title]",title)
//                                                          .addFormDataPart("assignment_override[lock_at]",due.toString())
//                                                          .build();
//
//            switch (apiProcess(url, body, true)) {
//                case 201:
//                    entity.setToken_count(token_amount);
//                    entity.setTimestamp(current_time);
//                    tokenRepository.save(entity);
//
//                    Assignment assignment = fetchAssignment(assignment_id);
//                    logRepository.save(createLog(user_id, "spend", cost, "Assignment: " + assignment.getName()));
//                    return new UseTokenResponse("success", "", token_amount);
//                default:
//                    return new UseTokenResponse("failed", "Unable to update tokens", token_amount);
//            }
//        }
//        return new UseTokenResponse("failed", "Insufficient token amount", token_amount);
//    }

    @Override
    public UseTokenResponse useToken(String user_id, String assignment_id, Integer cost) throws IOException, BadRequestException, JSONException {
        Optional<TokenCountEntity> optional = tokenRepository.findById(user_id);
        if (!optional.isPresent()) {
            LOGGER.error("Error: Student " + user_id + " is not in current database");
            throw new BadRequestException("Student " + user_id + " is not in current database");
        }
        TokenCountEntity entity = optional.get();
        Integer token_amount = entity.getToken_count();
        if (token_amount >= cost) {
            Date current_time = new Date();
            String title = "Resubmission";
            Date due =  new Date(current_time.getTime() + 24*60*60*1000);

            String resubmission_id = resubmissionsMap.get(assignment_id);
            URL url = UriComponentsBuilder
                    .fromUriString(CANVAS_API_ENDPOINT + "/courses/" + COURSE_ID + "/assignments/" + resubmission_id + "/overrides")
                    .build().toUri().toURL();
            RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("assignment_override[student_ids][]",user_id)
                    .addFormDataPart("assignment_override[title]",title)
                    .addFormDataPart("assignment_override[lock_at]",due.toString())
                    .build();

            switch (apiProcess(url, body, true)) {
                case 201:
                    token_amount -= cost;
                    entity.setToken_count(token_amount);
                    entity.setTimestamp(current_time);
                    tokenRepository.save(entity);

                    Assignment resubmission = fetchAssignment(resubmission_id);
                    Map<String, Student> studentMap = getStudents();
                    logRepository.save(createLog(user_id, studentMap.get(user_id).getName(), "spend", cost, "Assignment: " + resubmission.getName()));
                    return new UseTokenResponse("success", "", token_amount);
                case 400:
                    return new UseTokenResponse("failed", "Student already requested resubmission", token_amount);
                default:
                    return new UseTokenResponse("failed", "Unable to update tokens", token_amount);
            }
        }
        return new UseTokenResponse("failed", "Insufficient token amount", token_amount);
    }

    private class ExportResponse {
        public String getFileId() {
            return fileId;
        }

        public double getPercentComplete() {
            return percentComplete;
        }

        public String getStatus() {
            return status;
        }

        private String fileId;
        private double percentComplete;
        private String status;

        public ExportResponse(String fileId, double percentComplete, String status) {
            this.fileId = fileId;
            this.percentComplete = percentComplete;
            this.status = status;
        }
    }

    /**
     * Fetch grades of all students for a specific quiz
     *
     * @param quizId Quiz ID, can be looked up using List Assignments API
     * @return Map of quiz scores, key is student id, value is score of the quiz for this student
     * @throws IOException
     * @throws JSONException
     */
    private Map<String, Double> getStudentQuizScores(String quizId) throws IOException, JSONException {
        int page = 1;
        Map<String, Student> students = getStudents();
        Map<String, Double> quizScores = new HashMap<>();

        while (true) {
            String users_id = students.entrySet().stream().map(e -> "&student_ids%5B%5D=" + e.getValue().getId()).collect(Collectors.joining(""));
            URL url = new URL(CANVAS_API_ENDPOINT + "/courses/" + COURSE_ID + "/quizzes/" + quizId +
                    "/submissions?exclude_response_fields%5B%5D=preview_url&grouped=1&response_fields%5B%5D=assignment_id&response_fields%5B%5D=attachments&response_fields%5B%5D=attempt&response_fields%5B%5D=cached_due_date&response_fields%5B%5D=entered_grade&response_fields%5B%5D=entered_score&response_fields%5B%5D=excused&response_fields%5B%5D=grade&response_fields%5B%5D=grade_matches_current_submission&response_fields%5B%5D=grading_period_id&response_fields%5B%5D=id&response_fields%5B%5D=late&response_fields%5B%5D=late_policy_status&response_fields%5B%5D=missing&response_fields%5B%5D=points_deducted&response_fields%5B%5D=posted_at&response_fields%5B%5D=redo_request&response_fields%5B%5D=score&response_fields%5B%5D=seconds_late&response_fields%5B%5D=submission_type&response_fields%5B%5D=submitted_at&response_fields%5B%5D=url&response_fields%5B%5D=user_id&response_fields%5B%5D=workflow_state&student_ids%5B%5D="
                    + users_id + "&page=" + page + "&per_page=" + PER_PAGE);
            String response = apiProcess(url, true);
            JSONObject resultObj = new JSONObject(response);
            JSONArray result = resultObj.getJSONArray("quiz_submissions");

            for (int i = 0; i < result.length(); i++) {
                JSONObject jsonObject = result.getJSONObject(i);
                double kept_score = jsonObject.getDouble("kept_score"), max_score = jsonObject.getDouble("quiz_points_possible");
                double percentage_score = kept_score / max_score * 100;
                String studentId = String.valueOf(jsonObject.getInt("user_id"));
                quizScores.put(studentId, percentage_score);
            }
            if (result.length() < PER_PAGE)
                break;
            page++;
        }
        return quizScores;
    }

    @Override
    public List<AssignmentStatus> getAssignmentStatuses(String user_id) {
        LOGGER.info("Fetching assignment statuses for " + user_id);
        List<AssignmentStatus> assignmentStatuses = new ArrayList<>();
        resubmissionsMap.entrySet().stream().forEach(e -> {
            String assignmentId = e.getKey();
            String resubmissionId = e.getValue();
            try {
                assignmentStatuses.add(getAssignmentStatusForStudent(user_id, assignmentId, resubmissionId));
            } catch (IOException | JSONException ex) {
                ex.printStackTrace();
            }
        });
        return assignmentStatuses;
    }

    /**
     * List assignment submissions for a specific student
     *
     * @param user_id
     * @param assignmentId
     * @return
     */
    private AssignmentStatus getAssignmentStatusForStudent(String user_id, String assignmentId, String resubmissionId) throws IOException, JSONException {
        int page = 1;
        Assignment assignment = fetchAssignment(assignmentId);
        Assignment resubmission = fetchAssignment(resubmissionId);

        while (true) {
            URL url = UriComponentsBuilder
                    .fromUriString(CANVAS_API_ENDPOINT + "/courses/" + COURSE_ID + "/assignments/" + assignmentId + "/submissions")
                    .queryParam("page", page)
                    .queryParam("per_page", PER_PAGE)
                    .build().toUri().toURL();
            String response = apiProcess(url, true);
            JSONArray resultArray = new JSONArray(response);
            for (int i = 0; i < resultArray.length(); i++) {
                JSONObject submissionObj = resultArray.getJSONObject(i);
                String submissionUserId = submissionObj.getString("user_id");
                Double score = null;
                if (submissionUserId.equals(user_id)) {
                    score = submissionObj.isNull("score") ? null : submissionObj.getDouble("score");
                    //Doesn't have a grade yet or can't fetch grade
                    if (score == null) {
                        return new AssignmentStatus(assignment.getName(), assignment.getId(), assignment.getDueDate(), 0.0, assignment.getMaxPoints(), "Not graded yet", -1);
                    }
                    //Grades released
                    int tokens_required = (int) (assignment.getMaxPoints() - score);
                    if (!resubmission.getDueDate().equals("No Due Date")
                            && Instant.now().isAfter(Instant.parse(resubmission.getDueDate()))) {
                        return new AssignmentStatus(assignment.getName(),
                                assignment.getId(),
                                resubmission.getDueDate(),
                                score,
                                assignment.getMaxPoints(),
                                "overdue",
                                -1);
                    }
                    String status = StreamSupport.stream(
                            logRepository.findByUserIdAssignmentId(user_id, "Assignment: " + resubmission.getName()).spliterator(), false).count() > 0 ?
                            "requested" : "none";
                    return new AssignmentStatus(assignment.getName(),
                            assignment.getId(),
                            resubmission.getDueDate(),
                            score,
                            assignment.getMaxPoints(),
                            status,
                            tokens_required);
                }
            }
            if (resultArray.length() < PER_PAGE)
                break;
            page++;
        }
        return new AssignmentStatus(assignment.getName(),
                assignment.getId(),
                resubmission.getDueDate(),
                0.0,
                assignment.getMaxPoints(),
                "N/A",
                -1);
    }

    private Assignment fetchAssignment(String assignmentId) throws IOException, JSONException {
        URL url = UriComponentsBuilder.fromUriString(CANVAS_API_ENDPOINT + "/courses/" + COURSE_ID + "/assignments/" + assignmentId)
                .build().toUri().toURL();
        String response = apiProcess(url, true);
        JSONObject responseObj = new JSONObject(response);
        String dueAt = responseObj.getString("lock_at");
        if (dueAt == null || dueAt.equals("null")) {
            dueAt = "No Due Date";
        }
        double pointsPossible = responseObj.getDouble("points_possible");
        String name = responseObj.getString("name");
        return new Assignment(assignmentId, name, dueAt, pointsPossible);
    }

    @Override
    public UpdateTokenResponse updateToken(String user_id, Integer tokenNum) {
        Optional<TokenCountEntity> optional = tokenRepository.findById(user_id);
        if (optional.isPresent()) {
            TokenCountEntity entity = optional.get();
            entity.setToken_count(tokenNum);
            tokenRepository.save(entity);
            return new UpdateTokenResponse("complete", tokenNum);
        } else {
            LOGGER.error("Error: Student " + user_id + " does not exist in database");
            return new UpdateTokenResponse("failed", -1);
        }
    }
}