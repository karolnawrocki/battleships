package Battleships;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.TextInputDialog;
import javafx.scene.paint.Color;
import javafx.event.EventHandler;
import javafx.scene.Group;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.*;

public class Main extends Application {
    static final int TILE_SIZE = 40;

    private static int HEIGHT;
    private static int WIDTH;
    static final int GAP_BETWEEN_BOARDS = 10;

    private boolean playersTurn;
    private boolean shipsPlaced;

    private Scene scene;

    private Tile[][] enemyBoard;

    private Tile[][] board;

    private Group tileGroup = new Group();
    private Group enemyTileGroup = new Group();
    private Group shipGroup = new Group();
    private Group shotResultsGroup = new Group();

    private Stack<ShipSegment[]> shipsToPlace = new Stack<>();

    private int numberOfEnemyShipSegments = 0;
    private int numberOfMyShipSegments = 0;

    private ServerSocket server;
    private ObjectOutputStream output;
    private ObjectInputStream input;
    private Socket connection;
    private String serverIP;

    private Thread thread;

    static int getWIDTH() {
        return WIDTH;
    }

    private Parent createContent(){
        Pane root = new Pane();
        root.setPrefSize(TILE_SIZE * WIDTH * 2 + GAP_BETWEEN_BOARDS - 10,TILE_SIZE * HEIGHT - 10);

        root.getChildren().addAll(tileGroup,enemyTileGroup,shipGroup,shotResultsGroup);

        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                Tile tile = new Tile(x,y,true);
                board[x][y] = tile;
                tileGroup.getChildren().add(tile);

                Tile enemyTile = new Tile(x ,y,false);
                enemyBoard[x][y] = enemyTile;
                enemyTileGroup.getChildren().add(enemyTile);
            }
        }
        return root;
    }

    @Override
    public void start(Stage stage){
        Alert hostOrClientAlert = new Alert(Alert.AlertType.CONFIRMATION);
        hostOrClientAlert.setTitle("Battleships");
        hostOrClientAlert.setHeaderText("Launch client or server?");
        ButtonType clientButton = new ButtonType("Client");
        ButtonType serverButton = new ButtonType("Server");
        hostOrClientAlert.getButtonTypes().setAll(clientButton, serverButton);
        Optional<ButtonType> hostOrClientResult = hostOrClientAlert.showAndWait();

        if(hostOrClientResult.get() == serverButton){

            try{
                server = new ServerSocket(6666,2);
                waitForConnection();
                setupStreams();
                setupBoardSize(askUserAboutSize());
                sendBoardSizeToClient();

            }catch(IOException iOException){
                System.out.println("Server ended the connection");
                iOException.printStackTrace();
            }
            playersTurn = true;
            setupStage(stage);
        }

        else if(hostOrClientResult.get() == clientButton) {
            TextInputDialog textInputDialog = new TextInputDialog();
            textInputDialog.setHeaderText("Please input server IP");
            textInputDialog.setTitle("Battleships");
            textInputDialog.getEditor().setText("192.168.0.");
            textInputDialog.showAndWait();
            serverIP = textInputDialog.getEditor().getText();
            try {
                connectToServer();
                setupStreams();
                setupBoardSize(receiveBoardSizeFromServer());

            } catch (EOFException eofException) {
                System.out.println("client terminated the exception");
                eofException.printStackTrace();
            } catch (IOException ioException) {
                ioException.printStackTrace();
            }
            playersTurn = false;
            setupStage(stage);
        }
    }

    private void setupStage(Stage stage){
        board = new Tile[WIDTH][HEIGHT];
        enemyBoard = new Tile[WIDTH][HEIGHT];

        stage.setTitle("Place ship of size: " + Integer.toString(shipsToPlace.peek().length));
        stage.setResizable(false);
        this.scene = new Scene(createContent());

        thread = new Thread(()->{
            while (true) {
                try {
                    if (shipsPlaced && numberOfMyShipSegments != 0 && numberOfEnemyShipSegments != 0)
                        receiveShotCoordinatesFromEnemy();
                } catch (EOFException eofException) {

                    System.out.println("client terminated the exception");
                    eofException.printStackTrace();
                    Platform.exit();
                } catch (IOException ioException) {
                    ioException.printStackTrace();
                }
            }
        });
        thread.setDaemon(true);

        scene.setOnKeyPressed(new EventHandler<KeyEvent>() {
            @Override
            public void handle(KeyEvent event) {
                if(event.getCode() == KeyCode.R) {
                    Random random = new Random();
                    boolean isHorizontal;
                    int x;
                    int y;

                    while(!shipsToPlace.isEmpty()){
                        do{
                            isHorizontal = random.nextBoolean();
                            x = random.nextInt(WIDTH);
                            y = random.nextInt(HEIGHT);
                        }while(!canShipBePlaced(shipsToPlace.peek(),x,y,isHorizontal));
                        placeShip(shipsToPlace.pop(),x,y,isHorizontal);
                    }
                    stage.setTitle("Battleships");
                    handleBoardSetup();
                }
            }
        });
        scene.setOnMousePressed(new EventHandler<MouseEvent>() {
            @Override
            public void handle(MouseEvent event) {
                if(!shipsToPlace.empty()){
                    int x = (int)event.getX() / TILE_SIZE;
                    int y = (int)event.getY() / TILE_SIZE;
                    boolean isHorizontal;
                    if(event.getButton() == MouseButton.PRIMARY) isHorizontal = true;
                    else if(event.getButton() == MouseButton.SECONDARY) isHorizontal = false;
                    else return;
                    if(canShipBePlaced(shipsToPlace.peek(),x,y,isHorizontal)){
                        placeShip(shipsToPlace.pop(),x,y,isHorizontal);
                    }

                    if(!shipsToPlace.empty())
                        stage.setTitle("Place ship of size: " + Integer.toString(shipsToPlace.peek().length));
                    else{
                        stage.setTitle("Battleships");
                        handleBoardSetup();

                    }
                }
                else if(playersTurn && (int)event.getX() > (WIDTH*TILE_SIZE) + GAP_BETWEEN_BOARDS){
                    int x = ((int)event.getX() - (WIDTH*TILE_SIZE) - GAP_BETWEEN_BOARDS) / TILE_SIZE;
                    int y = (int)event.getY()  / TILE_SIZE;

                    shot(x, y);
                    try{
                        sendShotCoordinatesToEnemy(x,y);
                    }catch(Exception e){
                        e.printStackTrace();
                    }
                }
            }
        });
        stage.setScene(scene);
        stage.show();
    }

    private int askUserAboutSize(){
        Alert chooseSizeAlert = new Alert(Alert.AlertType.CONFIRMATION);
        chooseSizeAlert.setTitle("Battleships");
        chooseSizeAlert.setHeaderText("Choose board size");
        ButtonType button7 = new ButtonType("7x7");
        ButtonType button10 = new ButtonType("10x10");
        ButtonType button12 = new ButtonType("12x12");
        chooseSizeAlert.getButtonTypes().setAll(button7, button10, button12);
        Optional<ButtonType> chooseSizeResult = chooseSizeAlert.showAndWait();

        if(chooseSizeResult.get() == button7){
            return 7;
        }

        else if(chooseSizeResult.get() == button10){
            return 10;
        }

        else if(chooseSizeResult.get() == button12){
            return 12;
        }
        else
            return 0;
    }



    private void handleBoardSetup(){
        shipsPlaced = true;
        try{
            sendMyBoardToOtherPlayer();
            receiveEnemyBoard();
            thread.start();

        }catch(EOFException eofException){
            System.out.println("client terminated the exception");
            eofException.printStackTrace();
        }catch(IOException ioException){
            ioException.printStackTrace();
        }
    }

    private int receiveBoardSizeFromServer() throws IOException{
        do {
            try {
                int tempBoardSize = (int) input.readObject();
                System.out.println("board size: " + tempBoardSize);
                return tempBoardSize;
            }catch(ClassNotFoundException e){
                System.out.println("unknown data received");
            }
        }while(true);
    }

    private void receiveShotCoordinatesFromEnemy() throws IOException{
        do {
            try {
                Coordinates coords = (Coordinates) input.readObject();
                int x = coords.getX();
                int y = coords.getY();
                System.out.println("shot coordinates from enemy: " + x + ", " + y + " were received");
                Platform.runLater(()->{
                    showShotResultOnBoard(board[x][y].getHasShip(), true, x, y);
                    if(!board[x][y].getHasShip())
                        playersTurn = true;
                    else{
                        numberOfMyShipSegments--;
                        if(numberOfMyShipSegments == 0){
                            System.out.println("You lose!");
                            playersTurn = false;
                            Alert alert = new Alert(Alert.AlertType.INFORMATION);
                            alert.setTitle("You lose!");
                            alert.setHeaderText("Sorry, you are the loser!");
                            alert.showAndWait();
                            closeConnection();
                            Platform.exit();
                        }
                    }
                    System.out.println("My ship segments: " + numberOfMyShipSegments);
                });
                playersTurn = true;
                return;
            } catch (ClassNotFoundException e) {
                System.out.println("class not found");
                e.printStackTrace();
            } catch(ClassCastException classCastException){
                System.out.println("class cast exception");
                classCastException.printStackTrace();
            } catch (SocketException socketException){
                System.out.println("socket closed, exiting application");
                Platform.exit();
            } catch (EOFException eofException){
                System.out.println("client terminated the exception");
                eofException.printStackTrace();
                Platform.exit();
            }
        }while(true);
    }

    private void receiveEnemyBoard() throws IOException{
        do {
            try {
                Tile[][] enemyBoard = (Tile[][]) input.readObject();
                System.out.println("enemy board received");
                this.enemyBoard = enemyBoard.clone();
                return;
            }catch(ClassNotFoundException e){
                System.out.println("unknown data received");
            }
        }while(true);
    }

    private void sendShotCoordinatesToEnemy(int x, int y) throws IOException{
        try{
            output.writeObject(new Coordinates(x,y));
            System.out.println("shot coordinates: " + x + ", " + y + " were sent to enemy");
            if(numberOfEnemyShipSegments == 0){
                System.out.println("You won!");
                playersTurn = false;
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("You won!");
                alert.setHeaderText("Congratulations, you are the winner!");
                alert.showAndWait();
                closeConnection();
                Platform.exit();
            }
            output.flush();
        }catch(IOException e){
            e.printStackTrace();
            System.out.println("cannot send coordinates");
        }
    }

    private void sendMyBoardToOtherPlayer() throws IOException{
        try{
            output.writeObject(this.board.clone());
            System.out.println("board was sent to " + connection.getInetAddress().getHostName());
            output.flush();
        }catch(IOException e){
            e.printStackTrace();
            System.out.println("cannot send board");
        }
    }

    private void sendBoardSizeToClient(){
        try{
            output.writeObject(WIDTH);
            System.out.println("board size was sent to " + connection.getInetAddress().getHostName());
            output.flush();
        }catch(IOException e){
            e.printStackTrace();
            System.out.println("cannot send board size");
        }
    }

    private void connectToServer() throws IOException{
        System.out.println("attempting connection");
        connection = new Socket(InetAddress.getByName(serverIP), 6666);
        System.out.println("connection established! connected to: " + connection.getInetAddress().getHostName());
    }

    private void closeConnection(){
        System.out.println("closing connection");
        playersTurn = false;
        try{
            output.close();
            input.close();
            connection.close();
        }catch (IOException e){
            e.printStackTrace();
        }
    }

    private void waitForConnection() throws IOException{
        System.out.println("Waiting for someone to connect");
        connection = server.accept();
        System.out.println("Connected to " + connection.getInetAddress().getHostName());
    }

    private void setupStreams() throws IOException{
        output = new ObjectOutputStream(connection.getOutputStream());
        output.flush();
        input = new ObjectInputStream(connection.getInputStream());
        System.out.println("streams are now set up");
    }

    private void setupBoardSize(int size) {
        WIDTH = HEIGHT = size;
        if(size == 7){
            int[] shipsSizes = {1,2,3,4};
            setShipsToPlace(shipsSizes);
        }else if(size == 10){
            int[] shipsSizes = {2,2,2,3,3,4,5};
            setShipsToPlace(shipsSizes);
        }else if(size == 12){
            int[] shipsSizes = {1,1,1,2,2,3,3,3,4,4,5};
            setShipsToPlace(shipsSizes);
        }
        else
            System.out.println("wrong size");
    }

    private void setShipsToPlace(int[] shipsSizes){
        for(int i:shipsSizes){
            shipsToPlace.push(new ShipSegment[i]);
            numberOfEnemyShipSegments += i;
            numberOfMyShipSegments += i;
        }
    }

    private boolean canShipBePlaced(ShipSegment[] ship, int x, int y, boolean isHorizontal){
        for(int i = 0; i < ship.length; i++){
            if(isHorizontal){
                if(x+i >= 0 && y >= 0 && x+i< WIDTH && y < HEIGHT){
                    if(!board[x+i][y].isAvailable()) return false;
                }
                else return false;
            }
            else{
                if(x >= 0 && y+i >= 0 && x < WIDTH && y+i < HEIGHT){
                    if(!board[x][y+i].isAvailable()) return false;
                }
                else return false;
            }
        }
        return true;
    }

    private void setUnavailable(int x, int y){
        if(x >= 0 && y >= 0 && x < WIDTH && y < HEIGHT)
            board[x][y].setAvailable(false);
    }

    private void shot(int x, int y){
        if(!enemyBoard[x][y].isAlreadyClicked()) {
            if (enemyBoard[x][y].getHasShip()) {
                System.out.println("hit!");
                showShotResultOnBoard(true,false,x,y);
                numberOfEnemyShipSegments--;
            }
            else {
                System.out.println("miss!");
                playersTurn = false;
                showShotResultOnBoard(false,false,x,y);
            }
            enemyBoard[x][y].setAlreadyClicked(true);
        }
        else
            System.out.println("You have already shot this tile!");
    }

    private void showShotResultOnBoard(boolean shipSegmentDestroyed, boolean isItFriendlyBoard, int x, int y){
        Rectangle rectangle = new Rectangle(TILE_SIZE,TILE_SIZE);
        rectangle.setStroke(Color.GRAY);
        rectangle.setStrokeWidth(2);
        if(shipSegmentDestroyed)
            rectangle.setFill(Color.RED);
        else
            rectangle.setFill(Color.WHITE);
        if(isItFriendlyBoard)
            rectangle.relocate(x * TILE_SIZE, y * TILE_SIZE);
        else
            rectangle.relocate((x+ Main.getWIDTH()) * Main.TILE_SIZE + Main.GAP_BETWEEN_BOARDS , y * Main.TILE_SIZE);

        shotResultsGroup.getChildren().add(rectangle);
    }

    private void placeShip(ShipSegment[] ship, int x, int y, boolean isHorizontal){
        for(int i = 0; i < ship.length; i++){
            if(isHorizontal) {
                ship[i] = new ShipSegment(x + i, y);
                board[x+i][y].setHasShip(true);
                setUnavailable(x+i-1, y-1);
                setUnavailable(x+i, y-1);
                setUnavailable(x+i+1, y-1);
                setUnavailable(x+i-1, y);
                setUnavailable(x+i, y);
                setUnavailable(x+i+1, y);
                setUnavailable(x+i-1, y+1);
                setUnavailable(x+i, y+1);
                setUnavailable(x+i+1, y+1);
            }
            else{
                ship[i] = new ShipSegment(x, y + i);
                board[x][y+i].setHasShip(true);
                setUnavailable(x-1, y+i-1);
                setUnavailable(x, y+i-1);
                setUnavailable(x+1, y+i-1);
                setUnavailable(x-1, y+i);
                setUnavailable(x, y+i);
                setUnavailable(x+1, y+i);
                setUnavailable(x-1, y+i+1);
                setUnavailable(x, y+i+1);
                setUnavailable(x+1, y+i+1);
            }
            shipGroup.getChildren().add(ship[i]);
        }
    }
    public static void main(String[] args){
        launch(args);
    }
}