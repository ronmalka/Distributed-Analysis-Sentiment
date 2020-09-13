Distributed Analysis Sentiment
=========================

Short Description:
-----------------
The application takes a text file, where each line contains an reviews for some movie or book, Afterwards, the reviews are being analyzed by AWS EC2 instances, and once all analysis are done a summary webpage (HTML) is being produced for the client.

Workflow Description:
--------------------
Once the program stars running on the client, it checks if there is a running manager node on AWS EC2 service of the given account (If there isn't, it starts a new one) and sends it a SQS message through a single queue, that is used by all clients to send requests to the manager. This message contains the S3 URL of the input file and the SQS URL of the response queue.
The manager starts  new threads -  one does status checks for the workers every minute. Afterwards, it make new thread for every message from the clients that prosses the new task, and it make a new other thread  that checks for responses in an endless loop (until stopped), it use a thread pool to excute the new task thread and send messages to the workers. Once received a terminate message, the manager stops listening for new tasks, and waits until all the existing task accomplished. Once that happend, it terminates the workers, deletes all the queues and shutdown itself.
Every Worker node works in an endless loop until stopped, and takes messages from a single queue from the manager. Once a message has received, the worker analysis sentiment on the review by Stanford Core NLP library  , and sends an appropriate message to the manager once finished.
Running instructions:
--------------------
1. Store your AWS credentials in `~/.aws/credentials`
```
   [default]
   aws_access_key_id= ???
   aws_secret_access_key= ???
```
2. run using `java -jar LocalApp.jar inputFileName-1.json ... inputFileName-k.json  outputFileName-1.html outputFileName-k.html n (terminate)` (terminate is optional)

* Please note that by according to S3 limits, the input file can't be with a big case letter!

Security:
---------
The manager and all of the workers get temporary credentials from their IAM role. Therefore, we don't transfer them credentials at all, particulary not as plain text.

Scalability:
------------
I ran a few applications at a time to test the program, they all worked properly, finished properly and the results were correct.
While checking my implementation, I noticed that there is a built-in limitation in AWS EC2 regarding the maximum amount of instances we are able to run - maximum of 16 instances at the same time. According to that, I added this limitation to my implementation, so the application won't crush in case that the manager tries to create instances to a total amount of 16 or more. With the given limitations the program will be able to perform on large amount of clients thanks to a thread pool mechanism I used in the manager.

Persistence:
------------
If one of the workers is impaired we implemented a fail mechanism that uses the SQS time-out functionallity to resend the message into the input queue of the workers and activated a reboot function so that a new worker will replace the impaired one. Morever, if the worker encounters an exception while working it will send the exception to the manager and regroup to handle a new message.

Threads:
--------
We used threads in our Manager - one thread which operates the thread pool for the clients, another thread that checks the status of the workers, and a thread pool that processes the new task and the responses from the workers. This is the only place where I thought it is neccessary to use threads in out application, so I could handle a big amount of clients at the same time.

Termination:
--------
The termination process is well managed, and everything is closed once requested - local app, manager, workers and queues are deleted.

System:
--------
All the workers are working equally, they have access to the input queue where they fetch an assignment, work on it, and send the resulting work back to the output queue. They know nothing more than they need and they are treated equally, The manager is resposible of assembling the data and distribute it.
