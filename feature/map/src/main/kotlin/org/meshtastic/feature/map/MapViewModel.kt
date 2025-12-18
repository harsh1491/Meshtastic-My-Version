package org.meshtastic.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.meshtastic.core.data.repository.NodeRepository
import org.meshtastic.core.database.model.Node
import javax.inject.Inject

@HiltViewModel
class MapViewModel @Inject constructor(
    private val nodeRepository: NodeRepository
) : ViewModel() {

    // 1. FIX: Use 'ourNodeInfo' (found in your repository file)
    val ourNodeInfo: StateFlow<Node?> = nodeRepository.ourNodeInfo
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // 2. FIX: Remove 'isMeshServiceConnected' as it is missing from your repo.
    // Defaulting to true so the UI doesn't block features.
    val isConnected: StateFlow<Boolean> = flowOf(true)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // 3. FIX: Use 'nodeDBbyNum' and convert the Map to a List
    val nodesWithPosition: StateFlow<List<Node>> = nodeRepository.nodeDBbyNum
        .map { nodeMap ->
            nodeMap.values.filter { node ->
                // Filter logic: Must have position and not be "Me"

                // Note: Meshtastic stores position as Integers (e.g. 340000000)
                // We check if they are not zero.
                val lat = node.position?.latitudeI ?: 0
                val lon = node.position?.longitudeI ?: 0
                val hasPosition = lat != 0 && lon != 0

                val isNotMe = node.num != (ourNodeInfo.value?.num ?: 0)

                hasPosition && isNotMe
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}