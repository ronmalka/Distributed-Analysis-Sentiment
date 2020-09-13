

import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.PutObjectRequest;

import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import org.apache.commons.io.FileUtils;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class ResponseService implements Runnable {

    private String bucketName, workersToManager, taskId;
    private ConcurrentMap<String, NewTaskService.Task> tasks;
    private AmazonS3Client amazonS3Client;
    private AmazonSQSClient amazonSQSClient;
    private StringBuilder stringBuilder;


    private AtomicBoolean Done = new AtomicBoolean(false);
    private ArrayList<String> reviewsId;
    private String[] reviews;
    private int counter = 0 ;


    public ResponseService(String bucketName, String workersToManager, ConcurrentMap<String, NewTaskService.Task> tasks,
                           AmazonS3Client amazonS3Client, AmazonSQSClient amazonSQSClient, String taskId, int msgCounter) {
        this.bucketName = bucketName;
        this.workersToManager = workersToManager;
        this.tasks =tasks;
        this.amazonS3Client = amazonS3Client;
        this.amazonSQSClient = amazonSQSClient;
        this.taskId = taskId;
        reviewsId = new ArrayList<>();
        stringBuilder = new StringBuilder("");
        reviews = new String[msgCounter];
        System.out.println("ResponseService");
    }
    @Override
    public void run() {
        System.out.println("ResponseService : run");
        while (!Done.get()) {
            System.out.println("ResponseService : not done");
            Message response = Manager.getMessage(workersToManager);
            if (response != null) {
                System.out.println("ResponseService : Message not null");
                String reviewId  = response.getMessageAttributes().get("reviewId").getStringValue();
                String taskId = response.getMessageAttributes().get("id").getStringValue();
                int messageId = Integer.parseInt(response.getMessageAttributes().get("MessageId").getStringValue());
                System.out.println("ResponseService : this.taskId.equals(taskId) && !reviewsId.contains(reviewId) : "
                        + (this.taskId.equals(taskId) && !reviewsId.contains(reviewId)));
                if (this.taskId.equals(taskId) && !reviewsId.contains(reviewId)){
                    counter++;
                    System.out.println("ResponseService : run new review");
                    System.out.println("ResponseService : responser response " + counter +" messages");
                    reviewsId.add(reviewId);
                    reviews[messageId] = response.getBody();


                    amazonSQSClient.deleteMessage(workersToManager, response.getReceiptHandle());
                    NewTaskService.Task lastTask = tasks.get(taskId);
                    lastTask.decrease();
                    if (lastTask.isDone()){
                        System.out.println("ResponseService : run last task");

                        for (String review : reviews){
                            stringBuilder.append(review);
                            stringBuilder.append("\n");
                        }

                        createHtml(taskId,lastTask);
                        Done.compareAndSet(false,true);
                    }
                }else if (reviewsId.contains(reviewId)){
                    amazonSQSClient.deleteMessage(workersToManager, response.getReceiptHandle());
                }


            }
        }

        System.out.println("ResponseService : Done");
    }

    private void  createHtml(String lastTaskID, NewTaskService.Task lastTask) {
        System.out.println("ResponseService : createHtml");
        String keyValue = lastTaskID + "_summary.html";
        String result = S3ObjectToHtmlString();

        final File file = new File(keyValue);

        try {
            FileUtils.writeStringToFile(file, result);

        } catch (IOException e) {
            e.printStackTrace();
        }
        uploadS3(keyValue,lastTask,file);
    }

    private String S3ObjectToHtmlString() {
        System.out.println("ResponseService : S3ObjectToHtmlString");
//        BufferedReader reader = new BufferedReader(new InputStreamReader(summary.getObjectContent()));
        StringBuilder result = new StringBuilder();
        String header = "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "<title> Sentiment Analysis Summary\n" +
                "</title>\n" +
                "</head>\n" +
                "<body>\n";
        String footer = "</body>\n" +
                "</html>\n";
        result.append(header);
        result.append(stringBuilder.toString());
        result.append(footer);
        return result.toString();
    }

    private void uploadS3(final String outputKey, NewTaskService.Task lastTask, File fileToUpload) {
        System.out.println("ResponseService : uploadS3");

        amazonS3Client.putObject(new PutObjectRequest(bucketName,outputKey, fileToUpload)
                .withCannedAcl(CannedAccessControlList.PublicRead));
        Map<String,MessageAttributeValue> messageAttributeValueMap =
                new HashMap<>();
        messageAttributeValueMap.put("KEY" , new MessageAttributeValue()
                .withDataType("String")
                .withStringValue(outputKey));
        messageAttributeValueMap.put("outputUrl" , new MessageAttributeValue()
                .withDataType("String")
                .withStringValue(amazonS3Client.getResourceUrl(bucketName,outputKey)));
        amazonSQSClient.sendMessage(new SendMessageRequest(lastTask.getManagerToLocalSQS(), "finish task")
                .withMessageAttributes(messageAttributeValueMap));
        lastTask.Sent();
    }
}
