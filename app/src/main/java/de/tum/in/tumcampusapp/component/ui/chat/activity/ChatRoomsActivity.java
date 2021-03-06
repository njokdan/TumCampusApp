package de.tum.in.tumcampusapp.component.ui.chat.activity;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.support.design.widget.TabLayout;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.EditText;

import com.google.common.base.Optional;
import com.google.gson.Gson;

import java.io.IOException;
import java.util.List;

import de.tum.in.tumcampusapp.R;
import de.tum.in.tumcampusapp.api.app.TUMCabeClient;
import de.tum.in.tumcampusapp.api.app.exception.NoPrivateKey;
import de.tum.in.tumcampusapp.api.tumonline.TUMOnlineConst;
import de.tum.in.tumcampusapp.api.tumonline.TUMOnlineRequest;
import de.tum.in.tumcampusapp.component.other.generic.activity.ActivityForLoadingInBackground;
import de.tum.in.tumcampusapp.component.other.generic.adapter.NoResultsAdapter;
import de.tum.in.tumcampusapp.component.tumui.lectures.model.LecturesSearchRow;
import de.tum.in.tumcampusapp.component.tumui.lectures.model.LecturesSearchRowSet;
import de.tum.in.tumcampusapp.component.ui.chat.ChatRoomController;
import de.tum.in.tumcampusapp.component.ui.chat.adapter.ChatRoomListAdapter;
import de.tum.in.tumcampusapp.component.ui.chat.model.ChatMember;
import de.tum.in.tumcampusapp.component.ui.chat.model.ChatRoom;
import de.tum.in.tumcampusapp.component.ui.chat.model.ChatRoomAndLastMessage;
import de.tum.in.tumcampusapp.component.ui.chat.model.ChatVerification;
import de.tum.in.tumcampusapp.utils.Const;
import de.tum.in.tumcampusapp.utils.Utils;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import se.emilsjolander.stickylistheaders.StickyListHeadersListView;

/**
 * This activity presents the chat rooms of user's
 * lectures using the TUMOnline web service
 */
public class ChatRoomsActivity extends ActivityForLoadingInBackground<Void, List<ChatRoomAndLastMessage>> implements OnItemClickListener {
    private static final int CAMERA_REQUEST_CODE = 34;
    private static final int JOIN_ROOM_REQUEST_CODE = 22;

    private StickyListHeadersListView lvMyChatRoomList;

    private ChatRoom currentChatRoom;
    private ChatMember currentChatMember;
    private TUMOnlineRequest<LecturesSearchRowSet> requestHandler;
    private ChatRoomController manager;
    private int mCurrentMode = 1;
    private ChatRoomListAdapter chatRoomAdapter;

    public ChatRoomsActivity() {
        super(R.layout.activity_chat_rooms);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // bind UI elements
        lvMyChatRoomList = findViewById(R.id.lvMyChatRoomList);
        lvMyChatRoomList.setOnItemClickListener(this);

        manager = new ChatRoomController(this);

        //Load the lectures list
        requestHandler = new TUMOnlineRequest<>(TUMOnlineConst.Companion.getLECTURES_PERSONAL(), this, true);

        TabLayout tabLayout = findViewById(R.id.chat_rooms_tabs);
        // Create a tab listener that is called when the user changes tabs.
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                // show the given tab
                mCurrentMode = 1 - tab.getPosition();
                startLoading();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
                // hide the given tab
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                // probably ignore this event
            }
        });

        tabLayout.addTab(tabLayout.newTab()
                                  .setText(R.string.joined));
        tabLayout.addTab(tabLayout.newTab()
                                  .setText(R.string.not_joined));
    }

    @Override
    protected void onResume() {
        super.onResume();
        startLoading();
    }

    /**
     * Gets the saved local information for the user
     */
    private void populateCurrentChatMember() {
        if (currentChatMember == null) {
            currentChatMember = Utils.getSetting(this, Const.CHAT_MEMBER, ChatMember.class);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.menu_activity_chat_rooms, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int i = item.getItemId();
        if (i == R.id.action_add_chat_room) {
            newChatRoom();
            return true;
        } else if (i == R.id.action_join_chat_room) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                int permissionCheck = checkSelfPermission(Manifest.permission.CAMERA);
                if (permissionCheck == PackageManager.PERMISSION_DENIED) {
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST_CODE);
                } else {
                    startJoinRoom();
                }
            } else {
                startJoinRoom();
            }
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    private void startJoinRoom() {
        startActivityForResult(new Intent(this, JoinRoomScanActivity.class), JOIN_ROOM_REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            String name = data.getStringExtra("name");
            if (name.charAt(3) == ':') {
                createOrJoinChatRoom(name);
            } else {
                Utils.showToast(this, R.string.invalid_chat_room);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == CAMERA_REQUEST_CODE
            && grantResults != null && grantResults.length >= 1
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startJoinRoom();
        }
    }

    /**
     * Prompt the user to type in a name for the new chat room
     */
    private void newChatRoom() {
        // Set an EditText view to get user input
        final EditText input = new EditText(this);

        new AlertDialog.Builder(this)
                .setTitle(R.string.new_chat_room)
                .setMessage(R.string.new_chat_room_desc)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                    String value = input.getText()
                                        .toString();
                    String randId = Integer.toHexString((int) (Math.random() * 4096));
                    createOrJoinChatRoom(randId + ':' + value);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Creates a given chat room if it does not exist and joins it
     * Works asynchronously.
     */
    private void createOrJoinChatRoom(String name) {
        if (this.currentChatMember == null) {
            Utils.showToast(this, getString(R.string.chat_not_setup));
            return;
        }

        Utils.logv("create or join chat room " + name);
        currentChatRoom = new ChatRoom(name);

        try {
            TUMCabeClient.getInstance(this)
                         .createRoom(currentChatRoom, ChatVerification.Companion.getChatVerification(this, this.currentChatMember), new Callback<ChatRoom>() {
                             @Override
                             public void onResponse(Call<ChatRoom> call, Response<ChatRoom> response) {
                                 if (!response.isSuccessful()) {
                                     Utils.logv("Error creating&joining chat room: " + response.message());
                                     return;
                                 }

                                 // The POST request is successful: go to room. API should have auto joined it
                                 Utils.logv("Success creating&joining chat room: " + response.body());
                                 currentChatRoom = response.body();

                                 manager.join(currentChatRoom);

                                 // When we show joined chat rooms open chat room directly
                                 if (mCurrentMode == 1) {
                                     moveToChatActivity();
                                 } else { //Otherwise show a nice information, that we added the room
                                     final List<ChatRoomAndLastMessage> rooms = manager.getAllByStatus(mCurrentMode);

                                     runOnUiThread(() -> {
                                         chatRoomAdapter.updateRooms(rooms);
                                         Utils.showToast(ChatRoomsActivity.this, R.string.joined_chat_room);
                                     });
                                 }
                             }

                             @Override
                             public void onFailure(Call<ChatRoom> call, Throwable t) {
                                 Utils.log(t, "Failure creating/joining chat room - trying to GET it from the server");
                                 Utils.showToastOnUIThread(ChatRoomsActivity.this, R.string.activate_key);
                             }
                         });
        } catch (NoPrivateKey noPrivateKey) {
            this.finish();
        }
    }

    @Override
    protected List<ChatRoomAndLastMessage> onLoadInBackground(Void... arg) {
        Optional<LecturesSearchRowSet> lecturesList = requestHandler.fetch();
        if (lecturesList.isPresent()) {
            List<LecturesSearchRow> lectures = lecturesList.get()
                                                           .getLehrveranstaltungen();
            manager.replaceInto(lectures);
        }

        this.populateCurrentChatMember();

        // Try to restore joined chat rooms from server
        if (currentChatMember != null) {
            try {
                List<ChatRoom> rooms = TUMCabeClient.getInstance(this)
                                                    .getMemberRooms(currentChatMember.getId(), ChatVerification.Companion.getChatVerification(this, currentChatMember));
                manager.replaceIntoRooms(rooms);
            } catch (NoPrivateKey e) {
                this.finish();
            } catch (IOException e) {
                Utils.log(e);
            }
        }
        return manager.getAllByStatus(mCurrentMode);
    }

    @Override
    protected void onLoadFinished(List<ChatRoomAndLastMessage> result) {
        showLoadingEnded();
        if (result.isEmpty()) {
            lvMyChatRoomList.setAdapter(new NoResultsAdapter(this));
        } else {
            // set ListView to data via the LecturesListAdapter
            chatRoomAdapter = new ChatRoomListAdapter(this, result, mCurrentMode);
            lvMyChatRoomList.setAdapter(chatRoomAdapter);
        }
    }

    /**
     * Handle click on chat room
     */
    @Override
    public void onItemClick(AdapterView<?> a, View v, int position, long id) {
        ChatRoomAndLastMessage item = (ChatRoomAndLastMessage) lvMyChatRoomList.getItemAtPosition(position);

        // set bundle for LectureDetails and show it
        Bundle bundle = new Bundle();
        final Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtras(bundle);

        String chatRoomUid = item.getChatRoomDbRow()
                                 .getSemesterId() + ':' + item.getChatRoomDbRow()
                                                              .getName();
        this.createOrJoinChatRoom(chatRoomUid);
    }

    /**
     * Opens {@link ChatActivity}
     */
    private void moveToChatActivity() {
        // We need to move to the next activity now and provide the necessary data for it
        // We are sure that both currentChatRoom and currentChatMember exist
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra(Const.CURRENT_CHAT_ROOM, new Gson().toJson(currentChatRoom));
        startActivity(intent);
    }
}
