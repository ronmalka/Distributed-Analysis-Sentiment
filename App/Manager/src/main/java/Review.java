
public class Review {

    private String id,link,title,text,author,date;
    private int rating;

    public Review(String id, String link, String title, String text, String author, String date, int rating) {
        this.id = id;
        this.link = link;
        this.title = title;
        this.text = text;
        this.author = author;
        this.date = date;
        this.rating = rating;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getLink() {
        return link;
    }

    public void setLink(String link) {
        this.link = link;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public int getRating() {
        return rating;
    }

    public void setRating(int rating) {
        this.rating = rating;
    }
    @Override
    public String toString(){
        StringBuilder stringBuilder = new StringBuilder("") ;
        stringBuilder
                .append("<h4>ID: "+ id + " " + "</h4>" +"\n")
                .append("<h4>Link: "+ link+ " " + "</h4>" +"\n")
                .append("<h4>Title: " + title + " " +"</h4>" +"\n")
                .append("<h4>Text: " + text + " " +"</h4>" +"\n")
                .append("<h4>Author: " + author + " " +"</h4>" +"\n")
                .append("<h4>Date: " + date + " " +"</h4>" +"\n")
                .append("<h4>Rating: " + rating + " " +
                        "</h4>" +"\n");
        return stringBuilder.toString();
    }
    public String toStringEntity(){
        StringBuilder stringBuilder = new StringBuilder("") ;
        stringBuilder
                .append("ID: "+ id)
                .append("Link: "+ link)
                .append("Title: " + title)
                .append("Text: " + text)
                .append("Author: " + author)
                .append("Rating: " + rating);
        return stringBuilder.toString();
    }

}
