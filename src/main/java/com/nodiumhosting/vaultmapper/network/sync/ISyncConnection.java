package com.nodiumhosting.vaultmapper.network.sync;

import com.nodiumhosting.vaultmapper.map.VaultCell;

public interface ISyncConnection {
    void connect();

    void closeGracefully();

    void sendCellPacket(VaultCell cell);

    void sendMovePacket(String name, int cellX, int cellZ, float rotation);
}
