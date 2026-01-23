package com.souyu.common.manager;

import com.souyu.common.state.State;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StateDocument {
    @Id
    private String id; // taskId
    private State state;
}
