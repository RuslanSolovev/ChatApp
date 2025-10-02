package com.example.chatapp.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.DiscussionActivity
import com.example.chatapp.R
import com.example.chatapp.SozdanieBeseda
import com.example.chatapp.adapters.GroupDiscussionsAdapter
import com.example.chatapp.models.Discussion
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.database.*

class GroupChatsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: GroupDiscussionsAdapter
    private lateinit var dbRef: DatabaseReference
    private lateinit var fabCreateDiscussion: FloatingActionButton

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_discussions_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.rvDiscussions)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        fabCreateDiscussion = view.findViewById(R.id.fabCreateDiscussion)
        fabCreateDiscussion.setOnClickListener {
            if (isAdded && context != null) {
                startActivity(Intent(requireActivity(), SozdanieBeseda::class.java))
            }
        }

        adapter = GroupDiscussionsAdapter { discussion ->
            openDiscussion(discussion.discussionId)
        }
        recyclerView.adapter = adapter

        loadDiscussions()
    }

    private fun loadDiscussions() {
        dbRef = FirebaseDatabase.getInstance().getReference("discussions")
        dbRef.orderByChild("lastMessageTimestamp").addValueEventListener(
            object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!isAdded) return

                    val discussions = mutableListOf<Discussion>()
                    for (discussionSnapshot in snapshot.children) {
                        val discussion = discussionSnapshot.getValue(Discussion::class.java)
                        discussion?.let {
                            discussions.add(
                                it.copy(
                                    lastMessageText = it.lastMessageText ?: "Нет сообщений",
                                    lastMessageTimestamp = it.lastMessageTimestamp ?: 0L
                                )
                            )
                        }
                    }
                    adapter.submitList(discussions.reversed())
                }

                override fun onCancelled(error: DatabaseError) {
                    if (isAdded) {
                        // Обработка ошибки
                    }
                }
            }
        )
    }

    private fun openDiscussion(discussionId: String) {
        if (!isAdded) return

        val intent = Intent(requireContext(), DiscussionActivity::class.java)
        intent.putExtra("discussionId", discussionId)
        startActivity(intent)
        requireActivity().overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }
}