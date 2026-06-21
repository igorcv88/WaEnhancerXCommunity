package com.waex.pro;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

interface IProService {
    Bundle validateKeybox(String xmlContent);
    ParcelFileDescriptor convertAudioToOpus(in ParcelFileDescriptor inputPfd);
}
